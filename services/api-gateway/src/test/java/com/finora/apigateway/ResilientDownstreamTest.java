package com.finora.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.commons.resilience.DownstreamFailure;
import com.finora.commons.resilience.DownstreamPolicy;
import com.finora.commons.resilience.DownstreamRequest;
import com.finora.commons.resilience.DownstreamResponse;
import com.finora.commons.resilience.ResilientDownstream;
import com.finora.testkit.StubServer;
import com.finora.testkit.StubServer.Reply;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real HTTP against a loopback stub: timeouts, retries, idempotency rules, circuit breaker and bulkhead. */
class ResilientDownstreamTest {

    private StubServer stub;

    @BeforeEach
    void start() {
        stub = new StubServer();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private static DownstreamPolicy policy(int retries, long attemptMs, long totalMs) {
        return new DownstreamPolicy(Duration.ofMillis(500), Duration.ofMillis(attemptMs), Duration.ofMillis(totalMs),
                retries, Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(5), 20, 10, 50f,
                Duration.ofSeconds(10), 50);
    }

    private ResilientDownstream downstream(DownstreamPolicy policy) {
        return new ResilientDownstream("test", URI.create(stub.baseUrl()), policy);
    }

    private static DownstreamRequest get() {
        return new DownstreamRequest("GET", "/internal/v1/thing", Map.of(), null);
    }

    private static DownstreamRequest post(Map<String, String> headers) {
        return new DownstreamRequest("POST", "/internal/v1/thing", headers, "{}".getBytes());
    }

    private static DownstreamFailure.Kind kindOf(Throwable t) {
        return ((DownstreamFailure) t).kind();
    }

    @Test
    void successfulCallReturnsStatusHeadersAndBody() {
        stub.respond((r, n) -> new Reply(200, Map.of("ETag", "\"v1\"", "Content-Type", "application/json"), "{\"ok\":true}", 0));
        DownstreamResponse response = downstream(policy(2, 500, 1000)).send(get());
        assertThat(response.status()).isEqualTo(200);
        assertThat(new String(response.body())).isEqualTo("{\"ok\":true}");
        assertThat(response.headers().get("etag")).containsExactly("\"v1\"");
        assertThat(stub.callCount()).isEqualTo(1);
    }

    @Test
    void slowServiceTimesOutWithinTheAttemptBudget() {
        stub.respond((r, n) -> Reply.json(200, "{}").delayed(1500));
        ResilientDownstream d = downstream(policy(0, 250, 1000));
        long start = System.nanoTime();
        assertThatThrownBy(() -> d.send(get())).satisfies(t -> assertThat(kindOf(t)).isEqualTo(DownstreamFailure.Kind.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1200));
    }

    @Test
    void totalDeadlineBoundsRetriesOfSlowCalls() {
        stub.respond((r, n) -> Reply.json(200, "{}").delayed(2000));
        ResilientDownstream d = downstream(policy(5, 200, 600));
        long start = System.nanoTime();
        assertThatThrownBy(() -> d.send(get())).satisfies(t -> assertThat(kindOf(t)).isEqualTo(DownstreamFailure.Kind.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
        assertThat(stub.callCount()).isLessThanOrEqualTo(4);
    }

    @Test
    void idempotentGetIsRetriedUntilTheServiceRecovers() {
        stub.respond((r, n) -> n < 3 ? Reply.json(503, "{}") : Reply.json(200, "{\"ok\":true}"));
        DownstreamResponse response = downstream(policy(2, 500, 3000)).send(get());
        assertThat(response.status()).isEqualTo(200);
        assertThat(stub.callCount()).isEqualTo(3);
    }

    @Test
    void retriesStopAfterTheConfiguredMaximum() {
        stub.respond((r, n) -> Reply.json(503, "{}"));
        ResilientDownstream d = downstream(policy(2, 500, 3000));
        assertThatThrownBy(() -> d.send(get())).satisfies(t -> assertThat(kindOf(t)).isEqualTo(DownstreamFailure.Kind.UNAVAILABLE));
        assertThat(stub.callCount()).isEqualTo(3);
    }

    @Test
    void postWithoutIdempotencyKeyIsNeverRetriedOnFailure() {
        stub.respond((r, n) -> Reply.json(503, "{}"));
        ResilientDownstream d = downstream(policy(3, 500, 3000));
        assertThatThrownBy(() -> d.send(post(Map.of()))).isInstanceOf(DownstreamFailure.class);
        assertThat(stub.callCount()).isEqualTo(1);
    }

    @Test
    void postWithoutIdempotencyKeyIsNeverRetriedAfterATimeout() {
        stub.respond((r, n) -> Reply.json(201, "{}").delayed(800));
        ResilientDownstream d = downstream(policy(3, 200, 3000));
        assertThatThrownBy(() -> d.send(post(Map.of()))).satisfies(t -> assertThat(kindOf(t)).isEqualTo(DownstreamFailure.Kind.TIMEOUT));
        // The first attempt may have been processed: retrying could duplicate the effect.
        assertThat(stub.callCount()).isEqualTo(1);
    }

    @Test
    void postWithIdempotencyKeyIsRetriedAndCarriesTheSameKeyEachTime() {
        stub.respond((r, n) -> n == 1 ? Reply.json(503, "{}") : Reply.json(201, "{\"id\":1}"));
        DownstreamResponse response = downstream(policy(2, 500, 3000)).send(post(Map.of("Idempotency-Key", "key-123")));
        assertThat(response.status()).isEqualTo(201);
        assertThat(stub.received()).hasSize(2).allSatisfy(r -> assertThat(r.headers().get("Idempotency-Key")).isEqualTo("key-123"));
    }

    @Test
    void connectionRefusedIsRetriedEvenWithoutAnIdempotencyKeyBecauseNothingWasSent() {
        StubServer closed = new StubServer();
        String url = closed.baseUrl();
        closed.close();
        ResilientDownstream d = new ResilientDownstream("down", URI.create(url), policy(2, 300, 3000));
        long start = System.nanoTime();
        assertThatThrownBy(() -> d.send(post(Map.of()))).satisfies(t -> assertThat(kindOf(t)).isEqualTo(DownstreamFailure.Kind.UNAVAILABLE));
        // Three attempts separated by backoff (10 ms, ~20 ms) cannot finish instantly but must finish quickly.
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void serverErrorIsSurfacedAndNotRetried() {
        stub.respond((r, n) -> Reply.json(500, "{\"secret\":\"stack\"}"));
        ResilientDownstream d = downstream(policy(3, 500, 3000));
        assertThatThrownBy(() -> d.send(get())).satisfies(t -> {
            assertThat(kindOf(t)).isEqualTo(DownstreamFailure.Kind.BAD_RESPONSE);
            assertThat(t.getMessage()).doesNotContain("stack");
        });
        assertThat(stub.callCount()).isEqualTo(1);
    }

    @Test
    void clientErrorsPassThroughWithoutRetryOrBreakerPenalty() {
        stub.respond((r, n) -> Reply.json(404, "{\"code\":\"NOT_FOUND\"}"));
        ResilientDownstream d = downstream(policy(3, 500, 3000));
        for (int i = 0; i < 15; i++) {
            assertThat(d.send(get()).status()).isEqualTo(404);
        }
        assertThat(stub.callCount()).isEqualTo(15);
        assertThat(d.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndStopsCallingTheService() throws Exception {
        stub.respond((r, n) -> Reply.json(503, "{}"));
        DownstreamPolicy p = new DownstreamPolicy(Duration.ofMillis(500), Duration.ofMillis(500), Duration.ofSeconds(2), 0,
                Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(5), 4, 4, 50f, Duration.ofMillis(400), 50);
        ResilientDownstream d = downstream(p);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> d.send(get())).isInstanceOf(DownstreamFailure.class);
        }
        assertThat(stub.callCount()).isEqualTo(4);
        assertThat(d.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> d.send(get())).satisfies(t -> assertThat(kindOf(t)).isEqualTo(DownstreamFailure.Kind.CIRCUIT_OPEN));
        assertThat(stub.callCount()).isEqualTo(4);

        // After the wait the circuit half-opens, probes, and closes again once the service recovers.
        stub.respond((r, n) -> Reply.json(200, "{}"));
        Thread.sleep(500);
        assertThat(d.send(get()).status()).isEqualTo(200);
        assertThat(d.send(get()).status()).isEqualTo(200);
        assertThat(d.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void openCircuitIsNeverRetried() throws Exception {
        stub.respond((r, n) -> Reply.json(503, "{}"));
        DownstreamPolicy p = new DownstreamPolicy(Duration.ofMillis(500), Duration.ofMillis(500), Duration.ofSeconds(5), 3,
                Duration.ofMillis(300), Duration.ofMillis(500), Duration.ofMillis(5), 2, 2, 50f, Duration.ofSeconds(30), 50);
        ResilientDownstream d = downstream(p);
        assertThatThrownBy(() -> d.send(get())).isInstanceOf(DownstreamFailure.class);
        int callsAfterTrip = stub.callCount();
        long start = System.nanoTime();
        assertThatThrownBy(() -> d.send(get())).satisfies(t -> assertThat(kindOf(t)).isEqualTo(DownstreamFailure.Kind.CIRCUIT_OPEN));
        assertThat(stub.callCount()).isEqualTo(callsAfterTrip);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(250));
    }

    @Test
    void bulkheadRejectsExcessConcurrencyImmediately() throws Exception {
        stub.respond((r, n) -> Reply.json(200, "{}").delayed(700));
        DownstreamPolicy p = new DownstreamPolicy(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(4), 0,
                Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(5), 20, 10, 50f, Duration.ofSeconds(10), 2);
        ResilientDownstream d = downstream(p);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DownstreamResponse> a = pool.submit(() -> d.send(get()));
            Future<DownstreamResponse> b = pool.submit(() -> d.send(get()));
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (d.availableConcurrency() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            long start = System.nanoTime();
            assertThatThrownBy(() -> d.send(get())).satisfies(t -> assertThat(kindOf(t)).isEqualTo(DownstreamFailure.Kind.OVERLOADED));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(300));
            assertThat(a.get().status()).isEqualTo(200);
            assertThat(b.get().status()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(d.availableConcurrency()).isEqualTo(2);
    }
}
