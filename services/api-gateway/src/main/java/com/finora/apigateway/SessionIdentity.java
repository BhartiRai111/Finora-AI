package com.finora.apigateway;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who the browser session belongs to, as established by the session authority (identity-access). The gateway never
 * trusts a client-supplied identity or role; it only translates this into a short-lived internal token.
 * {@code csrfValid} says whether the request carried the CSRF token bound to this session.
 */
public record SessionIdentity(UUID userId, UUID sessionId, Map<UUID, SpaceMembership> memberships,
        boolean csrfValid) {

    public record SpaceMembership(UUID membershipId, String role, List<String> scopes, Instant stepUpUntil) {
    }
}
