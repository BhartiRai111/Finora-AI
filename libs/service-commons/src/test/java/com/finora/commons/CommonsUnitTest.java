package com.finora.commons;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.commons.correlation.CorrelationId;
import com.finora.commons.error.ErrorCode;
import com.finora.commons.error.ProblemWriter;
import com.finora.commons.security.InternalAuthException;
import com.finora.commons.security.InternalPrincipal;
import com.finora.commons.security.InternalTokenIssuer;
import com.finora.commons.security.InternalTokenVerifier;
import com.finora.commons.security.PrincipalKind;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommonsUnitTest {

    private static KeyPair newPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    @Test
    void correlationIdAcceptsOnlyCanonicalUuids() {
        assertThat(CorrelationId.isValid(UUID.randomUUID().toString())).isTrue();
        assertThat(CorrelationId.isValid("not-a-uuid")).isFalse();
        assertThat(CorrelationId.isValid("../../x")).isFalse();
        assertThat(CorrelationId.isValid(null)).isFalse();
        assertThat(CorrelationId.isValidTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")).isTrue();
        assertThat(CorrelationId.isValidTraceparent("00-short-bad-01")).isFalse();
    }

    @Test
    void problemBodiesCarryOnlyTheStableFields() {
        Map<String, Object> body = new ProblemWriter(null).body(ErrorCode.UPSTREAM_TIMEOUT, null, "rid", 5);
        assertThat(body).containsEntry("status", 504).containsEntry("code", "UPSTREAM_TIMEOUT")
                .containsEntry("request_id", "rid").containsEntry("retry_after_seconds", 5);
        assertThat(body.keySet()).containsExactly("type", "title", "status", "code", "request_id", "retry_after_seconds");
        assertThat(body.get("type")).isEqualTo("https://api.finora.example/problems/upstream-timeout");
    }

    @Test
    void everyErrorCodeHasAUniqueSlugAndAValidStatus() {
        assertThat(java.util.Arrays.stream(ErrorCode.values()).map(ErrorCode::slug)).doesNotHaveDuplicates();
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.status()).isBetween(400, 599);
        }
    }

    @Test
    void userTokenRoundTripsEveryClaim() throws Exception {
        KeyPair gateway = newPair();
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
        InternalTokenIssuer issuer = new InternalTokenIssuer("gateway", (ECPrivateKey) gateway.getPrivate(),
                Duration.ofMinutes(2), clock);
        InternalTokenVerifier verifier = new InternalTokenVerifier("finance", "gateway",
                Map.of("gateway", (ECPublicKey) gateway.getPublic()), Duration.ofMinutes(10), Duration.ofSeconds(30), clock);
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        Instant stepUp = Instant.parse("2026-03-01T10:05:00Z");

        InternalPrincipal p = verifier.verify(issuer.issueUser("finance", user, session, tenant, membership, "ADMIN",
                List.of("a", "b"), stepUp));

        assertThat(p.kind()).isEqualTo(PrincipalKind.USER);
        assertThat(p.subject()).isEqualTo(user.toString());
        assertThat(p.sessionId()).isEqualTo(session);
        assertThat(p.tenantId()).isEqualTo(tenant);
        assertThat(p.membershipId()).isEqualTo(membership);
        assertThat(p.role()).isEqualTo("ADMIN");
        assertThat(p.scopes()).containsExactly("a", "b");
        assertThat(p.stepUpUntil()).isEqualTo(stepUp);
        assertThat(p.expiresAt()).isEqualTo(Instant.parse("2026-03-01T10:02:00Z"));
    }

    @Test
    void tokenIsRejectedOnceItsLifetimePlusSkewHasPassed() throws Exception {
        KeyPair pair = newPair();
        Instant t0 = Instant.parse("2026-03-01T10:00:00Z");
        InternalTokenIssuer issuer = new InternalTokenIssuer("gateway", (ECPrivateKey) pair.getPrivate(),
                Duration.ofMinutes(2), Clock.fixed(t0, ZoneOffset.UTC));
        String token = issuer.issueAnonymous("finance");
        InternalTokenVerifier atExpiryPlusSkew = verifierAt(pair, t0.plus(Duration.ofMinutes(2)).plusSeconds(29));
        assertThat(atExpiryPlusSkew.verify(token).kind()).isEqualTo(PrincipalKind.ANONYMOUS);
        InternalTokenVerifier afterSkew = verifierAt(pair, t0.plus(Duration.ofMinutes(2)).plusSeconds(31));
        assertThatThrownBy(() -> afterSkew.verify(token)).isInstanceOfSatisfying(InternalAuthException.class,
                e -> assertThat(e.reason()).isEqualTo(InternalAuthException.Reason.EXPIRED));
    }

    private static InternalTokenVerifier verifierAt(KeyPair pair, Instant now) {
        return new InternalTokenVerifier("finance", "gateway", Map.of("gateway", (ECPublicKey) pair.getPublic()),
                Duration.ofMinutes(10), Duration.ofSeconds(30), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void serviceTokenFromTheGatewayNameIsStillOnlyAsTrustedAsItsKey() throws Exception {
        KeyPair real = newPair();
        KeyPair attacker = newPair();
        Clock clock = Clock.systemUTC();
        InternalTokenVerifier verifier = new InternalTokenVerifier("finance", "gateway",
                Map.of("gateway", (ECPublicKey) real.getPublic()), Duration.ofMinutes(10), Duration.ofSeconds(30), clock);
        String forged = new InternalTokenIssuer("gateway", (ECPrivateKey) attacker.getPrivate(), Duration.ofMinutes(2), clock)
                .issueUser("finance", UUID.randomUUID(), UUID.randomUUID(), null, null, null, List.of(), null);
        assertThatThrownBy(() -> verifier.verify(forged)).isInstanceOfSatisfying(InternalAuthException.class,
                e -> assertThat(e.reason()).isEqualTo(InternalAuthException.Reason.BAD_SIGNATURE));
    }
}
