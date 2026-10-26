package com.finora.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.commons.security.InternalTokenIssuer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Service-authentication checks for every private service: no token, forged, tampered, expired, wrong-audience,
 * wrong-algorithm and wrong-kind tokens are refused with the same generic problem document, valid ones are accepted,
 * and a user's token cannot cross into another workspace.
 */
public abstract class AbstractInternalServiceTest extends AbstractServiceTest {

    private static final String PING = "/internal/v1/ping";

    protected String audience() {
        return env.getRequiredProperty("spring.application.name");
    }

    private HttpResponse<String> ping(String token) throws Exception {
        return send("GET", PING, token == null ? Map.of() : Map.of("Authorization", "Bearer " + token), null);
    }

    private void assertRejected(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("X-Internal-Auth")).contains("rejected");
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(response.body()).contains("\"request_id\"").doesNotContain("Exception").doesNotContain("at com.");
    }

    @Test
    void validServiceTokenIsAccepted() throws Exception {
        HttpResponse<String> response = ping(TestTokens.service(audience()));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"principalKind\":\"SERVICE\"");
    }

    @Test
    void validGatewayUserTokenIsAcceptedOnInternalPath() throws Exception {
        assertThat(ping(TestTokens.gatewayUser(audience(), UUID.randomUUID())).statusCode()).isEqualTo(200);
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        assertRejected(ping(null), 401);
        assertThat(ping(null).headers().firstValue("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    void garbageTokenIsRejected() throws Exception {
        assertRejected(ping("not-a-jwt"), 401);
    }

    @Test
    void tokenForAnotherServiceIsRejected() throws Exception {
        assertRejected(ping(TestTokens.service("some-other-service")), 401);
    }

    @Test
    void tamperedSignatureIsRejected() throws Exception {
        String token = TestTokens.service(audience());
        // Alter a character in the middle of the signature segment (the final character carries only padding bits).
        int position = token.lastIndexOf('.') + 20;
        char original = token.charAt(position);
        String tampered = token.substring(0, position) + (original == 'A' ? 'B' : 'A') + token.substring(position + 1);
        assertThat(tampered).isNotEqualTo(token).hasSize(token.length());
        assertRejected(ping(tampered), 401);
    }

    @Test
    void tokenSignedByAnUntrustedKeyIsRejected() throws Exception {
        // Claims itself as the trusted caller but is signed with a different key.
        InternalTokenIssuer forger = new InternalTokenIssuer(TestTokens.CALLER, TestKeys.privateKey("attacker"),
                Duration.ofMinutes(2), Clock.systemUTC());
        assertRejected(ping(forger.issueService(audience(), null)), 401);
    }

    @Test
    void tokenFromAnUnknownIssuerIsRejected() throws Exception {
        InternalTokenIssuer stranger = new InternalTokenIssuer("stranger", TestKeys.privateKey("stranger"),
                Duration.ofMinutes(2), Clock.systemUTC());
        assertRejected(ping(stranger.issueService(audience(), null)), 401);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        Clock past = TestTokens.fixedAt(Instant.now().minus(Duration.ofHours(1)));
        assertRejected(ping(TestTokens.issuer(TestTokens.CALLER, Duration.ofMinutes(2), past)
                .issueService(audience(), null)), 401);
    }

    @Test
    void tokenIssuedInTheFutureIsRejected() throws Exception {
        Clock future = TestTokens.fixedAt(Instant.now().plus(Duration.ofHours(1)));
        assertRejected(ping(TestTokens.issuer(TestTokens.CALLER, Duration.ofMinutes(2), future)
                .issueService(audience(), null)), 401);
    }

    @Test
    void tokenWithExcessiveLifetimeIsRejected() throws Exception {
        assertRejected(ping(TestTokens.issuer(TestTokens.CALLER, Duration.ofHours(24), Clock.systemUTC())
                .issueService(audience(), null)), 401);
    }

    @Test
    void unsignedAlgNoneTokenIsRejected() throws Exception {
        PlainJWT plain = new PlainJWT(new JWTClaimsSet.Builder().issuer(TestTokens.CALLER).subject(TestTokens.CALLER)
                .audience(audience()).issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .jwtID(UUID.randomUUID().toString()).claim("knd", "SERVICE").build());
        assertRejected(ping(plain.serialize()), 401);
    }

    @Test
    void symmetricAlgorithmTokenIsRejected() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .issuer(TestTokens.CALLER).subject(TestTokens.CALLER).audience(audience()).issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(60))).jwtID(UUID.randomUUID().toString())
                .claim("knd", "SERVICE").build());
        jwt.sign(new MACSigner(new byte[32]));
        assertRejected(ping(jwt.serialize()), 401);
    }

    @Test
    void userTokenFromANonGatewayIssuerIsRejected() throws Exception {
        // Only the gateway may assert an end user.
        String token = TestTokens.issuer(TestTokens.CALLER).issueUser(audience(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, java.util.List.of(), null);
        assertRejected(ping(token), 401);
    }

    @Test
    void anonymousTokenIsNotAllowedOnInternalPath() throws Exception {
        assertRejected(ping(TestTokens.gatewayAnonymous(audience())), 403);
    }

    @Test
    void serviceTokenIsNotAllowedOnThePublicApiPath() throws Exception {
        UUID space = UUID.randomUUID();
        assertRejected(send("GET", "/v1/spaces/" + space + "/anything",
                Map.of("Authorization", "Bearer " + TestTokens.service(audience())), null), 403);
    }

    @Test
    void userTokenCannotReachAnotherWorkspace() throws Exception {
        UUID own = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        HttpResponse<String> response = send("GET", "/v1/spaces/" + other + "/anything",
                Map.of("Authorization", "Bearer " + TestTokens.gatewayUser(audience(), own)), null);
        assertRejected(response, 404);
    }

    @Test
    void userTokenForTheMatchingWorkspaceReachesTheServiceItself() throws Exception {
        UUID own = UUID.randomUUID();
        HttpResponse<String> response = send("GET", "/v1/spaces/" + own + "/anything",
                Map.of("Authorization", "Bearer " + TestTokens.gatewayUser(audience(), own)), null);
        // No such business route exists yet: the 404 comes from the service, not from the authentication filter.
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("X-Internal-Auth")).isEmpty();
    }

    @Test
    void healthEndpointsNeedNoToken() throws Exception {
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void errorBodiesNeverContainInternalDetail() throws Exception {
        HttpResponse<String> response = send("POST", "/internal/v1/ping", Map.of("Authorization",
                "Bearer " + TestTokens.service(audience())), "{}");
        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(response.body()).contains("METHOD_NOT_ALLOWED").doesNotContain("Exception").doesNotContain("org.spring");
    }

    @Test
    void sensitiveActuatorEndpointsAreNotExposedEvenToAuthenticatedCallers() throws Exception {
        for (String path : new String[] {"/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/configprops"}) {
            assertThat(send("GET", path, Map.of("Authorization", "Bearer " + TestTokens.service(audience())), null)
                    .statusCode()).as(path).isIn(403, 404);
        }
    }

    @Test
    void readinessRequiresTrustedIssuers() {
        assertThat(env.getProperty("management.endpoint.health.group.readiness.include")).contains("internalToken");
    }
}
