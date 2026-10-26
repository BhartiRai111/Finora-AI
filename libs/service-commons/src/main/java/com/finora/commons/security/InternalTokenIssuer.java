package com.finora.commons.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints short-lived, audience-restricted internal tokens (ES256). Tokens are never issued to browsers.
 * The gateway uses {@link #issueUser} and {@link #issueAnonymous}; services use {@link #issueService}.
 */
public final class InternalTokenIssuer {

    public static final String CLAIM_KIND = "knd";
    public static final String CLAIM_SESSION = "sid";
    public static final String CLAIM_TENANT = "tid";
    public static final String CLAIM_MEMBERSHIP = "mid";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_SCOPES = "scp";
    public static final String CLAIM_STEP_UP = "stp";
    public static final String ANONYMOUS_SUBJECT = "anonymous";

    private final String issuerName;
    private final ECPrivateKey key;
    private final Duration ttl;
    private final Clock clock;

    public InternalTokenIssuer(String issuerName, ECPrivateKey key, Duration ttl, Clock clock) {
        this.issuerName = issuerName;
        this.key = key;
        this.ttl = ttl;
        this.clock = clock;
    }

    public String issuerName() {
        return issuerName;
    }

    public String issueUser(String audience, UUID userId, UUID sessionId, UUID tenantId, UUID membershipId,
            String role, List<String> scopes, Instant stepUpUntil) {
        JWTClaimsSet.Builder claims = base(audience, userId.toString(), PrincipalKind.USER)
                .claim(CLAIM_SESSION, sessionId.toString());
        if (tenantId != null) {
            claims.claim(CLAIM_TENANT, tenantId.toString());
        }
        if (membershipId != null) {
            claims.claim(CLAIM_MEMBERSHIP, membershipId.toString());
        }
        if (role != null) {
            claims.claim(CLAIM_ROLE, role);
        }
        claims.claim(CLAIM_SCOPES, scopes == null ? List.of() : scopes);
        if (stepUpUntil != null) {
            claims.claim(CLAIM_STEP_UP, stepUpUntil.getEpochSecond());
        }
        return sign(claims.build());
    }

    public String issueAnonymous(String audience) {
        return sign(base(audience, ANONYMOUS_SUBJECT, PrincipalKind.ANONYMOUS).build());
    }

    /** A workload-identity token: subject equals issuer; optionally acting on behalf of a tenant. */
    public String issueService(String audience, UUID onBehalfOfTenant) {
        JWTClaimsSet.Builder claims = base(audience, issuerName, PrincipalKind.SERVICE);
        if (onBehalfOfTenant != null) {
            claims.claim(CLAIM_TENANT, onBehalfOfTenant.toString());
        }
        return sign(claims.build());
    }

    private JWTClaimsSet.Builder base(String audience, String subject, PrincipalKind kind) {
        Instant now = clock.instant();
        return new JWTClaimsSet.Builder()
                .issuer(issuerName)
                .subject(subject)
                .audience(audience)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .jwtID(UUID.randomUUID().toString())
                .claim(CLAIM_KIND, kind.name());
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(issuerName).build(), claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign internal token", e);
        }
    }
}
