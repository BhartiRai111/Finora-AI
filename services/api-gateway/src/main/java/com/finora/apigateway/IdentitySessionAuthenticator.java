package com.finora.apigateway;

import com.finora.commons.correlation.CorrelationId;
import com.finora.commons.resilience.DownstreamFailure;
import com.finora.commons.resilience.DownstreamRequest;
import com.finora.commons.resilience.DownstreamResponse;
import com.finora.commons.resilience.ResilientDownstream;
import com.finora.commons.security.InternalTokenIssuer;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates the browser session cookie against identity-access, the session authority. The cookie value is sent in a
 * request body to a private endpoint, authenticated with the gateway's own service token; it is never logged, put in a
 * URL, or forwarded to any other service. There is no cache: a revoked or expired session stops working on the very
 * next request. If identity-access cannot answer, the failure propagates (the caller gets 503), so the gateway
 * never treats "could not check" as "authenticated" or as "logged out".
 */
public class IdentitySessionAuthenticator implements SessionAuthenticator {

    static final String CSRF_HEADER = "X-CSRF-Token";

    private final ResilientDownstream identityAccess;
    private final InternalTokenIssuer issuer;
    private final ObjectMapper mapper;
    private final String cookieName;
    private final Duration timeout;

    public IdentitySessionAuthenticator(ResilientDownstream identityAccess, InternalTokenIssuer issuer,
            ObjectMapper mapper, String cookieName, Duration timeout) {
        this.identityAccess = identityAccess;
        this.issuer = issuer;
        this.mapper = mapper;
        this.cookieName = cookieName;
        this.timeout = timeout;
    }

    @Override
    public Optional<SessionIdentity> authenticate(HttpServletRequest request) {
        String sessionToken = cookie(request);
        if (sessionToken == null || sessionToken.isEmpty() || sessionToken.length() > 128) {
            return Optional.empty();
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("session_token", sessionToken);
        String csrf = request.getHeader(CSRF_HEADER);
        if (csrf != null && csrf.length() <= 128) {
            body.put("csrf_token", csrf);
        }
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + issuer.issueService("identity-access", null),
                "Content-Type", "application/json",
                CorrelationId.HEADER, String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
        DownstreamResponse response = identityAccess.send(new DownstreamRequest("POST", "/internal/v1/sessions/validate",
                headers, mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8), true), timeout,
                timeout.multipliedBy(2));
        if (response.status() != 200 || response.headers().containsKey("x-internal-auth")) {
            // identity-access refused our own token or misbehaved: a server-side fault, not a logged-out user.
            throw new DownstreamFailure(DownstreamFailure.Kind.BAD_RESPONSE,
                    "identity-access answered session validation with status " + response.status(), null);
        }
        JsonNode node = mapper.readTree(response.body());
        if (!node.path("valid").asBoolean(false)) {
            return Optional.empty();
        }
        try {
            Instant stepUp = node.path("step_up_until").isString() ? Instant.parse(node.path("step_up_until").asString()) : null;
            Map<UUID, SessionIdentity.SpaceMembership> memberships = new LinkedHashMap<>();
            for (JsonNode m : node.path("memberships")) {
                memberships.put(UUID.fromString(m.path("workspace_id").asString()), new SessionIdentity.SpaceMembership(
                        UUID.fromString(m.path("membership_id").asString()), m.path("role").asString(), List.of(), stepUp));
            }
            return Optional.of(new SessionIdentity(UUID.fromString(node.path("user_id").asString()),
                    UUID.fromString(node.path("session_id").asString()), Map.copyOf(memberships),
                    node.path("csrf_valid").asBoolean(false), node.path("mfa_pending").asBoolean(false)));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw new DownstreamFailure(DownstreamFailure.Kind.BAD_RESPONSE, "malformed session validation response", e);
        }
    }

    private String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (cookieName.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
