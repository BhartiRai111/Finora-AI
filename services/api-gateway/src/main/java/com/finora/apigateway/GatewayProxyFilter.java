package com.finora.apigateway;

import com.finora.commons.correlation.CorrelationId;
import com.finora.commons.error.ErrorCode;
import com.finora.commons.error.ProblemWriter;
import com.finora.commons.resilience.DownstreamFailure;
import com.finora.commons.resilience.DownstreamRequest;
import com.finora.commons.resilience.DownstreamResponse;
import com.finora.commons.resilience.ResilientDownstream;
import com.finora.commons.security.InternalAuthFilter;
import com.finora.commons.security.InternalTokenIssuer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The public edge. For every {@code /v1/**} request: validate the request shape, match one route, rate limit,
 * authenticate the session, mint a short-lived internal token for the single owning service, forward an allowlisted
 * set of headers through the resilient client, and map failures to safe problem documents.
 *
 * <p>Anything the client sends that could spoof internal identity (Authorization, cookies, forwarded-for, tenant or
 * role headers) is dropped; only headers on the allowlists cross the boundary in either direction.
 */
public class GatewayProxyFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayProxyFilter.class);
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final Set<String> REQUEST_HEADERS = Set.of("accept", "accept-language", "content-type", "if-match",
            "if-none-match", "idempotency-key", "user-agent");
    private static final Set<String> RESPONSE_HEADERS = Set.of("content-type", "etag", "location", "retry-after",
            "content-disposition", "idempotency-replayed", "deprecation", "sunset", "link", "content-language");
    private static final Pattern SPACE_PATH = Pattern.compile("^/v1/spaces/([^/]+)(/.*)?$");
    private static final Pattern BAD_PATH = Pattern.compile("(?i)(%2e|%2f|%5c|%00|%25|\\\\|;|\\.\\.|//|[\\x00-\\x1f\\x7f])");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[\\x21-\\x7e]{1,128}$");

    private final RouteTable routes;
    private final Map<String, ResilientDownstream> downstreams;
    private final SessionAuthenticator authenticator;
    private final InternalTokenIssuer issuer;
    private final TokenBucketRateLimiter limiter;
    private final GatewayProperties props;
    private final ProblemWriter problems;

    public GatewayProxyFilter(RouteTable routes, Map<String, ResilientDownstream> downstreams,
            SessionAuthenticator authenticator, InternalTokenIssuer issuer, TokenBucketRateLimiter limiter,
            GatewayProperties props, ProblemWriter problems) {
        this.routes = routes;
        this.downstreams = Map.copyOf(downstreams);
        this.authenticator = authenticator;
        this.issuer = issuer;
        this.limiter = limiter;
        this.props = props;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !(path.equals("/v1") || path.startsWith("/v1/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rawPath = request.getRequestURI();
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if (BAD_PATH.matcher(rawPath).find()) {
            problems.write(request, response, ErrorCode.BAD_REQUEST, "Invalid path", null);
            return;
        }
        Optional<Route> match = routes.match(rawPath);
        if (match.isEmpty()) {
            problems.write(request, response, ErrorCode.NOT_FOUND, null, null);
            return;
        }
        if (!METHODS.contains(method)) {
            problems.write(request, response, ErrorCode.METHOD_NOT_ALLOWED, null, null);
            return;
        }
        Route route = match.get();
        String idemKey = request.getHeader("Idempotency-Key");
        if (idemKey != null && !IDEMPOTENCY_KEY.matcher(idemKey).matches()) {
            problems.write(request, response, ErrorCode.BAD_REQUEST, "Invalid Idempotency-Key", null);
            return;
        }
        byte[] body = new byte[0];
        if (BODY_METHODS.contains(method)) {
            if (!contentTypeAllowed(request.getContentType(), route, request.getContentLengthLong())) {
                problems.write(request, response, ErrorCode.UNSUPPORTED_MEDIA_TYPE, null, null);
                return;
            }
            long limit = route.uploads() ? props.getMaxUploadBytes() : props.getMaxBodyBytes();
            if (request.getContentLengthLong() > limit) {
                problems.write(request, response, ErrorCode.PAYLOAD_TOO_LARGE, null, null);
                return;
            }
            try (InputStream in = request.getInputStream()) {
                body = in.readNBytes((int) Math.min(limit, Integer.MAX_VALUE - 8) + 1);
            }
            if (body.length > limit) {
                problems.write(request, response, ErrorCode.PAYLOAD_TOO_LARGE, null, null);
                return;
            }
        }

        Optional<SessionIdentity> identity = authenticator.authenticate(request);
        if (identity.isEmpty() && route.access() == Route.Access.SESSION_REQUIRED) {
            response.setHeader("WWW-Authenticate", "Cookie");
            problems.write(request, response, ErrorCode.AUTH_REQUIRED, null, null);
            return;
        }

        String subject = identity.map(i -> "u:" + i.userId()).orElseGet(() -> "ip:" + request.getRemoteAddr());
        if (props.getRateLimit().isEnabled()) {
            GatewayProperties.RateLimit rl = props.getRateLimit();
            boolean strict = route.strictRate();
            String key = (strict ? "auth|" : "api|") + (strict ? "ip:" + request.getRemoteAddr() : subject);
            TokenBucketRateLimiter.Decision decision = limiter.tryAcquire(key,
                    strict ? rl.getAuthCapacity() : rl.getCapacity(),
                    strict ? rl.getAuthRefillPerSecond() : rl.getRefillPerSecond());
            if (!decision.allowed()) {
                problems.write(request, response, ErrorCode.RATE_LIMITED, null, decision.retryAfterSeconds());
                return;
            }
        }

        String token;
        try {
            token = mintToken(request, route, identity);
        } catch (NotMemberException e) {
            problems.write(request, response, ErrorCode.NOT_FOUND, null, null);
            return;
        }

        ResilientDownstream downstream = downstreams.get(route.service());
        if (downstream == null) {
            LOG.error("No downstream configured for service {}", route.service());
            problems.write(request, response, ErrorCode.UPSTREAM_UNAVAILABLE, null, 5);
            return;
        }
        String pathAndQuery = rawPath + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        DownstreamRequest outbound = new DownstreamRequest(method, pathAndQuery,
                outboundHeaders(request, token), body);
        boolean read = method.equals("GET");
        Duration attempt = read ? props.getReadTimeout() : props.getWriteTimeout();
        Duration total = attempt.multipliedBy(Math.max(1, Math.min(2, props.getMaxRetries() + 1)));
        DownstreamResponse upstream;
        try {
            upstream = downstream.send(outbound, attempt, total);
        } catch (DownstreamFailure failure) {
            LOG.warn("Downstream {} failed for {} {}: {} ({})", route.service(), method, rawPath, failure.kind(),
                    failure.getMessage());
            ErrorCode code = failure.errorCode();
            problems.write(request, response, code, null, code == ErrorCode.UPSTREAM_UNAVAILABLE ? 5 : null);
            return;
        }
        if (upstream.headers().containsKey(InternalAuthFilter.REJECTED_HEADER.toLowerCase(Locale.ROOT))) {
            // The service refused our own token: a misconfigured hop, never something to show the end user as 401/403.
            LOG.error("Service {} rejected the gateway's internal token for {} {} (status {})", route.service(), method,
                    rawPath, upstream.status());
            problems.write(request, response, ErrorCode.UPSTREAM_ERROR, null, null);
            return;
        }
        copyResponse(upstream, route, response);
    }

    private String mintToken(HttpServletRequest request, Route route, Optional<SessionIdentity> identity) {
        String audience = route.service();
        if (identity.isEmpty()) {
            return issuer.issueAnonymous(audience);
        }
        SessionIdentity id = identity.get();
        Matcher m = SPACE_PATH.matcher(request.getRequestURI());
        if (m.matches()) {
            UUID space;
            try {
                space = UUID.fromString(m.group(1));
            } catch (IllegalArgumentException e) {
                throw new NotMemberException();
            }
            SessionIdentity.SpaceMembership membership = id.memberships().get(space);
            if (membership == null) {
                throw new NotMemberException();
            }
            return issuer.issueUser(audience, id.userId(), id.sessionId(), space, membership.membershipId(),
                    membership.role(), membership.scopes(), membership.stepUpUntil());
        }
        return issuer.issueUser(audience, id.userId(), id.sessionId(), null, null, null, List.of(), null);
    }

    private Map<String, String> outboundHeaders(HttpServletRequest request, String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if (REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, request.getHeader(name));
            }
        }
        headers.put("Authorization", "Bearer " + token);
        headers.put(CorrelationId.HEADER, String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
        String traceparent = request.getHeader(CorrelationId.TRACEPARENT);
        headers.put(CorrelationId.TRACEPARENT, CorrelationId.isValidTraceparent(traceparent) ? traceparent
                : "00-" + UUID.randomUUID().toString().replace("-", "") + "-"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-01");
        return headers;
    }

    private void copyResponse(DownstreamResponse upstream, Route route, HttpServletResponse response)
            throws IOException {
        response.setStatus(upstream.status());
        upstream.headers().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            boolean allowed = RESPONSE_HEADERS.contains(lower) || (route.setsCookies() && lower.equals("set-cookie"));
            if (allowed) {
                values.forEach(v -> response.addHeader(name, v));
            }
        });
        response.getOutputStream().write(upstream.body());
    }

    private static boolean contentTypeAllowed(String contentType, Route route, long contentLength) {
        if (contentType == null) {
            return contentLength <= 0;
        }
        String base = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (base.equals("application/json")) {
            return true;
        }
        return route.uploads() && (base.equals("multipart/form-data") || base.equals("application/octet-stream")
                || base.equals("text/csv"));
    }

    private static final class NotMemberException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotMemberException() {
            super(null, null, false, false);
        }
    }
}
