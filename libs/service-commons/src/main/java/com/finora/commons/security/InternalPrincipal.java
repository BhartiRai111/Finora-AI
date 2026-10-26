package com.finora.commons.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The verified identity behind an internal request. Built only from a token that passed every check. */
public record InternalPrincipal(
        PrincipalKind kind,
        String issuer,
        String subject,
        UUID sessionId,
        UUID tenantId,
        UUID membershipId,
        String role,
        List<String> scopes,
        Instant stepUpUntil,
        String tokenId,
        Instant expiresAt) {

    public static final String REQUEST_ATTRIBUTE = InternalPrincipal.class.getName() + ".value";
}
