package com.finora.apigateway;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * TEST ONLY. Stands in for the session authority that does not exist yet: a request is "logged in" when it carries
 * {@code X-Test-Session: member}. Never present on the production classpath.
 */
class HeaderTestSessionAuthenticator implements SessionAuthenticator {

    static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID SESSION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final UUID SPACE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID MEMBERSHIP = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Override
    public Optional<SessionIdentity> authenticate(HttpServletRequest request) {
        if (!"member".equals(request.getHeader("X-Test-Session"))) {
            return Optional.empty();
        }
        return Optional.of(new SessionIdentity(USER, SESSION, Map.of(SPACE,
                new SessionIdentity.SpaceMembership(MEMBERSHIP, "OWNER", List.of("finance:read"), Instant.EPOCH)),
                true));
    }
}
