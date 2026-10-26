package com.finora.commons.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/**
 * One guarded downstream (a private service). Per call: bulkhead -> total deadline -> retry loop, where each attempt
 * passes the circuit breaker and has its own timeout.
 *
 * <p>Retry rules (PD-24, Step 08b §3.1): only requests that cannot duplicate an effect are retried (idempotent methods
 * or an {@code Idempotency-Key}), and only for transport failures, timeouts and 502/503/504. A refused connection is
 * retried for any request because nothing was sent. An open circuit or a full bulkhead is never retried.
 */
public final class ResilientDownstream {

    private static final Set<String> RESTRICTED = Set.of("host", "connection", "content-length", "expect", "upgrade");

    private final String name;
    private final URI base;
    private final HttpClient client;
    private final DownstreamPolicy policy;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final RetryTemplate retryTemplate;
    private final RetryTemplate connectOnlyRetryTemplate;

    public ResilientDownstream(String name, URI base, DownstreamPolicy policy) {
        this(name, base, policy, HttpClient.newBuilder()
                .connectTimeout(policy.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }

    public ResilientDownstream(String name, URI base, DownstreamPolicy policy, HttpClient client) {
        this.name = name;
        this.base = base;
        this.client = client;
        this.policy = policy;
        this.breaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(policy.circuitWindow())
                .minimumNumberOfCalls(policy.circuitMinCalls())
                .failureRateThreshold(policy.circuitFailureRatePercent())
                .waitDurationInOpenState(policy.circuitOpenWait())
                .permittedNumberOfCallsInHalfOpenState(2)
                .build());
        this.bulkhead = Bulkhead.of(name, BulkheadConfig.custom()
                .maxConcurrentCalls(policy.maxConcurrentCalls())
                .maxWaitDuration(Duration.ZERO)
                .build());
        this.retryTemplate = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(policy.maxRetries())
                .delay(policy.initialBackoff())
                .jitter(policy.backoffJitter())
                .multiplier(2.0)
                .maxDelay(policy.maxBackoff())
                .predicate(ResilientDownstream::isRetryableFailure)
                .build());
        this.connectOnlyRetryTemplate = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(policy.maxRetries())
                .delay(policy.initialBackoff())
                .jitter(policy.backoffJitter())
                .multiplier(2.0)
                .maxDelay(policy.maxBackoff())
                .predicate(t -> t instanceof NeverSentFailure)
                .build());
    }

    public String name() {
        return name;
    }

    public CircuitBreaker.State circuitState() {
        return breaker.getState();
    }

    public int availableConcurrency() {
        return bulkhead.getMetrics().getAvailableConcurrentCalls();
    }

    /** Sends the request or throws {@link DownstreamFailure}. A returned response always has status below 500. */
    public DownstreamResponse send(DownstreamRequest request) {
        return send(request, policy.attemptTimeout(), policy.totalDeadline());
    }

    /** As {@link #send(DownstreamRequest)} with per-call deadlines (for example reads versus writes). */
    public DownstreamResponse send(DownstreamRequest request, Duration attemptTimeout, Duration totalDeadline) {
        if (!bulkhead.tryAcquirePermission()) {
            throw new DownstreamFailure(DownstreamFailure.Kind.OVERLOADED, name + ": bulkhead full", null);
        }
        long deadlineNanos = System.nanoTime() + totalDeadline.toNanos();
        try {
            // Non-idempotent requests still get connect-failure retries: nothing was sent, so nothing can duplicate.
            RetryTemplate template = request.retryable() ? retryTemplate : connectOnlyRetryTemplate;
            try {
                return template.execute(() -> attempt(request, deadlineNanos, attemptTimeout));
            } catch (RetryException e) {
                Throwable last = e.getCause();
                if (last instanceof DownstreamFailure failure) {
                    throw failure;
                }
                throw new DownstreamFailure(DownstreamFailure.Kind.UNAVAILABLE, name + ": retries exhausted", last);
            }
        } finally {
            bulkhead.releasePermission();
        }
    }

    private DownstreamResponse attempt(DownstreamRequest request, long deadlineNanos, Duration attemptTimeout) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new DownstreamFailure(DownstreamFailure.Kind.TIMEOUT, name + ": total deadline exceeded", null);
        }
        try {
            breaker.acquirePermission();
        } catch (CallNotPermittedException e) {
            throw new NonRetryableFailure(DownstreamFailure.Kind.CIRCUIT_OPEN, name + ": circuit open", e);
        }
        Duration timeout = Duration.ofNanos(Math.min(remaining, attemptTimeout.toNanos()));
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            future = client.sendAsync(toHttp(request), HttpResponse.BodyHandlers.ofByteArray());
            HttpResponse<byte[]> response = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            long elapsed = System.nanoTime() - started;
            int status = response.statusCode();
            if (status >= 500) {
                breaker.onError(elapsed, TimeUnit.NANOSECONDS, new IOException("status " + status));
                return failedStatus(status);
            }
            breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
            return new DownstreamResponse(status, response.headers().map(), response.body());
        } catch (TimeoutException e) {
            cancel(future);
            breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, e);
            throw new DownstreamFailure(DownstreamFailure.Kind.TIMEOUT, name + ": attempt timed out", e);
        } catch (InterruptedException e) {
            cancel(future);
            Thread.currentThread().interrupt();
            breaker.releasePermission();
            throw new NonRetryableFailure(DownstreamFailure.Kind.UNAVAILABLE, name + ": interrupted", e);
        } catch (ExecutionException e) {
            breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, e.getCause());
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                throw new DownstreamFailure(DownstreamFailure.Kind.TIMEOUT, name + ": " + cause.getClass().getSimpleName(), cause);
            }
            if (isNeverSent(cause)) {
                // Connection could not be established: nothing reached the service, so any request is safe to retry.
                throw new NeverSentFailure(name + ": cannot connect", cause);
            }
            throw new DownstreamFailure(DownstreamFailure.Kind.UNAVAILABLE, name + ": transport failure", cause);
        }
    }

    private DownstreamResponse failedStatus(int status) {
        DownstreamFailure.Kind kind = (status == 502 || status == 503 || status == 504)
                ? DownstreamFailure.Kind.UNAVAILABLE : DownstreamFailure.Kind.BAD_RESPONSE;
        if (kind == DownstreamFailure.Kind.BAD_RESPONSE) {
            // A plain 500 means the service handled (or half-handled) the request: surface, never retry.
            throw new NonRetryableFailure(kind, name + ": upstream status " + status, null);
        }
        throw new DownstreamFailure(kind, name + ": upstream status " + status, null);
    }

    private HttpRequest toHttp(DownstreamRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base.toString() + request.pathAndQuery()));
        request.headers().forEach((k, v) -> {
            if (!RESTRICTED.contains(k.toLowerCase(Locale.ROOT))) {
                builder.header(k, v);
            }
        });
        builder.method(request.method(), request.body().length == 0
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(request.body()));
        return builder.build();
    }

    private static void cancel(CompletableFuture<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    private static boolean isNeverSent(Throwable cause) {
        return cause instanceof HttpConnectTimeoutException
                || (cause instanceof ConnectException && !(cause instanceof ClosedChannelException));
    }

    /** Called by the retry policy: decides from the thrown failure whether another attempt is allowed. */
    private static boolean isRetryableFailure(Throwable t) {
        if (t instanceof NonRetryableFailure) {
            return false;
        }
        return t instanceof DownstreamFailure failure
                && (failure.kind() == DownstreamFailure.Kind.TIMEOUT || failure.kind() == DownstreamFailure.Kind.UNAVAILABLE);
    }

    /** A failure that must surface immediately (open circuit, server error that may have taken effect). */
    private static final class NonRetryableFailure extends DownstreamFailure {
        private static final long serialVersionUID = 1L;

        NonRetryableFailure(Kind kind, String message, Throwable cause) {
            super(kind, message, cause);
        }
    }

    /** Connection never established. */
    private static final class NeverSentFailure extends DownstreamFailure {
        private static final long serialVersionUID = 1L;

        NeverSentFailure(String message, Throwable cause) {
            super(Kind.UNAVAILABLE, message, cause);
        }
    }
}
