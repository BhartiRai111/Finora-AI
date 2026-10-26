package com.finora.commons.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.ECPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Verifies internal tokens. Only ES256 is accepted (no "none", no HMAC), the issuer must be configured,
 * the audience must be exactly this service, lifetimes are bounded, and kind-specific claims are required.
 */
public final class InternalTokenVerifier {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final String audience;
    private final String gatewayIssuer;
    private final Map<String, ECPublicKey> trustedIssuers;
    private final Duration maxTtl;
    private final Duration skew;
    private final Clock clock;

    public InternalTokenVerifier(String audience, String gatewayIssuer, Map<String, ECPublicKey> trustedIssuers,
            Duration maxTtl, Duration skew, Clock clock) {
        this.audience = audience;
        this.gatewayIssuer = gatewayIssuer;
        this.trustedIssuers = Map.copyOf(trustedIssuers);
        this.maxTtl = maxTtl;
        this.skew = skew;
        this.clock = clock;
    }

    public boolean hasTrustedIssuers() {
        return !trustedIssuers.isEmpty();
    }

    public String audience() {
        return audience;
    }

    public String gatewayIssuer() {
        return gatewayIssuer;
    }

    public InternalPrincipal verify(String token) throws InternalAuthException {
        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            jwt = SignedJWT.parse(token);
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException | RuntimeException e) {
            throw new InternalAuthException(InternalAuthException.Reason.MALFORMED);
        }
        if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())) {
            throw new InternalAuthException(InternalAuthException.Reason.UNSUPPORTED_ALGORITHM);
        }
        String issuer = claims.getIssuer();
        ECPublicKey key = issuer == null ? null : trustedIssuers.get(issuer);
        if (key == null) {
            throw new InternalAuthException(InternalAuthException.Reason.UNTRUSTED_ISSUER);
        }
        try {
            if (!jwt.verify(new ECDSAVerifier(key))) {
                throw new InternalAuthException(InternalAuthException.Reason.BAD_SIGNATURE);
            }
        } catch (JOSEException e) {
            throw new InternalAuthException(InternalAuthException.Reason.BAD_SIGNATURE);
        }

        List<String> audiences = claims.getAudience();
        if (audiences == null || audiences.size() != 1 || !audience.equals(audiences.get(0))) {
            throw new InternalAuthException(InternalAuthException.Reason.WRONG_AUDIENCE);
        }
        Instant now = clock.instant();
        Date exp = claims.getExpirationTime();
        Date iat = claims.getIssueTime();
        if (exp == null || iat == null) {
            throw new InternalAuthException(InternalAuthException.Reason.MISSING_CLAIM);
        }
        if (now.isAfter(exp.toInstant().plus(skew))) {
            throw new InternalAuthException(InternalAuthException.Reason.EXPIRED);
        }
        if (iat.toInstant().isAfter(now.plus(skew))
                || (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now.plus(skew)))) {
            throw new InternalAuthException(InternalAuthException.Reason.NOT_YET_VALID);
        }
        if (Duration.between(iat.toInstant(), exp.toInstant()).compareTo(maxTtl) > 0) {
            throw new InternalAuthException(InternalAuthException.Reason.TTL_TOO_LONG);
        }
        String jti = claims.getJWTID();
        String subject = claims.getSubject();
        if (jti == null || jti.isBlank() || subject == null || subject.isBlank()) {
            throw new InternalAuthException(InternalAuthException.Reason.MISSING_CLAIM);
        }

        PrincipalKind kind;
        try {
            kind = PrincipalKind.valueOf(String.valueOf(claims.getClaim(InternalTokenIssuer.CLAIM_KIND)));
        } catch (IllegalArgumentException e) {
            throw new InternalAuthException(InternalAuthException.Reason.INVALID_CLAIM);
        }
        UUID sessionId = null;
        UUID tenantId = uuidOrNull(claims, InternalTokenIssuer.CLAIM_TENANT);
        UUID membershipId = uuidOrNull(claims, InternalTokenIssuer.CLAIM_MEMBERSHIP);
        String role = stringOrNull(claims, InternalTokenIssuer.CLAIM_ROLE);
        List<String> scopes = List.of();
        Instant stepUp = null;
        switch (kind) {
            case USER -> {
                requireGateway(issuer);
                if (!UUID_PATTERN.matcher(subject).matches()) {
                    throw new InternalAuthException(InternalAuthException.Reason.INVALID_CLAIM);
                }
                sessionId = uuidOrNull(claims, InternalTokenIssuer.CLAIM_SESSION);
                if (sessionId == null) {
                    throw new InternalAuthException(InternalAuthException.Reason.MISSING_CLAIM);
                }
                scopes = stringList(claims, InternalTokenIssuer.CLAIM_SCOPES);
                Object stp = claims.getClaim(InternalTokenIssuer.CLAIM_STEP_UP);
                if (stp instanceof Number n) {
                    stepUp = Instant.ofEpochSecond(n.longValue());
                }
            }
            case ANONYMOUS -> {
                requireGateway(issuer);
                if (!InternalTokenIssuer.ANONYMOUS_SUBJECT.equals(subject) || tenantId != null) {
                    throw new InternalAuthException(InternalAuthException.Reason.INVALID_CLAIM);
                }
            }
            case SERVICE -> {
                if (!subject.equals(issuer)) {
                    throw new InternalAuthException(InternalAuthException.Reason.INVALID_CLAIM);
                }
            }
            default -> throw new InternalAuthException(InternalAuthException.Reason.INVALID_CLAIM);
        }
        return new InternalPrincipal(kind, issuer, subject, sessionId, tenantId, membershipId, role, scopes, stepUp, jti,
                exp.toInstant());
    }

    private void requireGateway(String issuer) throws InternalAuthException {
        if (!gatewayIssuer.equals(issuer)) {
            throw new InternalAuthException(InternalAuthException.Reason.INVALID_CLAIM);
        }
    }

    private static UUID uuidOrNull(JWTClaimsSet claims, String name) throws InternalAuthException {
        Object value = claims.getClaim(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s) || !UUID_PATTERN.matcher(s).matches()) {
            throw new InternalAuthException(InternalAuthException.Reason.INVALID_CLAIM);
        }
        return UUID.fromString(s);
    }

    private static String stringOrNull(JWTClaimsSet claims, String name) throws InternalAuthException {
        Object value = claims.getClaim(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s) || s.isBlank() || s.length() > 64) {
            throw new InternalAuthException(InternalAuthException.Reason.INVALID_CLAIM);
        }
        return s;
    }

    private static List<String> stringList(JWTClaimsSet claims, String name) throws InternalAuthException {
        Object value = claims.getClaim(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new InternalAuthException(InternalAuthException.Reason.INVALID_CLAIM);
        }
        for (Object item : list) {
            if (!(item instanceof String)) {
                throw new InternalAuthException(InternalAuthException.Reason.INVALID_CLAIM);
            }
        }
        return list.stream().map(Object::toString).toList();
    }
}
