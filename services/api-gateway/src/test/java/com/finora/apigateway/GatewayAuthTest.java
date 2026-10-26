package com.finora.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.commons.security.InternalPrincipal;
import com.finora.commons.security.InternalTokenVerifier;
import com.finora.commons.security.PrincipalKind;
import com.finora.testkit.StubServer;
import com.finora.testkit.StubServer.Reply;
import com.finora.testkit.TestKeys;
import com.finora.testkit.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The gateway with its REAL session authenticator (no test double for authentication): sessions are validated by an
 * HTTP call to a loopback stand-in for identity-access, which this test controls. Cross-process behaviour with the
 * real identity-access service is covered separately in the e2e module.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "finora.gateway.read-timeout=700ms",
        "finora.gateway.write-timeout=700ms",
        "finora.gateway.rate-limit.enabled=false",
        "finora.gateway.allowed-origins=https://app.example.test"})
class GatewayAuthTest {

    private static final String COOKIE = "__Host-finora_sid";
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID SESSION = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Stubs.register(registry);
        registry.add("finora.security.internal-token.signing.private-key-path",
                () -> TestKeys.privatePem(TestTokens.GATEWAY).toString());
    }

    @Value("${local.server.port}")
    int port;

    /** identity-access stand-in: "good" is a live session, "stale" is expired/revoked/unknown, csrf must be "csrf-1". */
    @BeforeEach
    void reset() {
        Stubs.resetAll();
        Stubs.get("identity-access").respond((r, n) -> {
            if (!r.path().equals("/internal/v1/sessions/validate")) {
                return Reply.json(200, "{}");
            }
            boolean good = r.body().contains("\"session_token\":\"good\"");
            boolean csrf = r.body().contains("\"csrf_token\":\"csrf-1\"");
            return Reply.json(200, good
                    ? "{\"valid\":true,\"user_id\":\"" + USER + "\",\"session_id\":\"" + SESSION + "\",\"csrf_valid\":" + csrf + "}"
                    : "{\"valid\":false}");
        });
    }

    private HttpResponse<String> call(String method, String path, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> h(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private InternalPrincipal verify(String authorization, String audience) throws Exception {
        assertThat(authorization).startsWith("Bearer ");
        return new InternalTokenVerifier(audience, TestTokens.GATEWAY, Map.of(TestTokens.GATEWAY, TestKeys.publicKey(TestTokens.GATEWAY)),
                Duration.ofMinutes(10), Duration.ofSeconds(30), Clock.systemUTC()).verify(authorization.substring(7));
    }

    private static final String SPACE = "/v1/spaces/" + UUID.randomUUID();

    // ---- session validation ----

    @Test
    void protectedRouteWithoutACookieIsRejectedWithoutAskingIdentityAccess() throws Exception {
        assertThat(call("GET", "/v1/me", Map.of(), null).statusCode()).isEqualTo(401);
        assertThat(Stubs.get("identity-access").callCount()).isZero();
    }

    @Test
    void liveSessionIsForwardedAsAVerifiedUserTokenAndTheCookieNeverLeavesTheGateway() throws Exception {
        HttpResponse<String> r = call("GET", "/v1/me", h("Cookie", COOKIE + "=good", "X-Request-Id", "7f1c2a9e-3b4d-4c5e-8f60-123456789abc"), null);
        assertThat(r.statusCode()).isEqualTo(200);
        StubServer identity = Stubs.get("identity-access");
        assertThat(identity.received()).hasSize(2); // validation, then the forwarded /v1/me
        StubServer.Received validation = identity.received().get(0);
        InternalPrincipal caller = verify(validation.headers().get("Authorization"), "identity-access");
        assertThat(caller.kind()).isEqualTo(PrincipalKind.SERVICE);
        assertThat(caller.issuer()).isEqualTo("gateway");
        assertThat(validation.headers()).doesNotContainKey("Cookie");
        assertThat(validation.headers().get("X-Request-Id")).isEqualTo("7f1c2a9e-3b4d-4c5e-8f60-123456789abc");
        StubServer.Received forwarded = identity.received().get(1);
        assertThat(forwarded.path()).isEqualTo("/v1/me");
        assertThat(forwarded.headers()).doesNotContainKey("Cookie");
        InternalPrincipal principal = verify(forwarded.headers().get("Authorization"), "identity-access");
        assertThat(principal.kind()).isEqualTo(PrincipalKind.USER);
        assertThat(principal.subject()).isEqualTo(USER.toString());
        assertThat(principal.sessionId()).isEqualTo(SESSION);
    }

    @Test
    void expiredRevokedOrUnknownSessionsAreRejected() throws Exception {
        for (String cookie : new String[] {COOKIE + "=stale", COOKIE + "=" + "z".repeat(500), COOKIE + "=", "other=good", "finora_sid=good"}) {
            Stubs.resetAll();
            HttpResponse<String> r = call("GET", SPACE + "/transactions", h("Cookie", cookie), null);
            assertThat(r.statusCode()).as(cookie).isEqualTo(401);
            assertThat(Stubs.get("finance").callCount()).as(cookie).isZero();
        }
    }

    @Test
    void identityAccessOutageIsAnErrorNotAnAuthenticationDecision() throws Exception {
        Stubs.get("identity-access").respond((r, n) -> Reply.json(503, "{}"));
        HttpResponse<String> r = call("GET", SPACE + "/transactions", h("Cookie", COOKIE + "=good"), null);
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(Stubs.get("finance").callCount()).isZero();
    }

    @Test
    void identityAccessRefusingTheGatewaysTokenFailsClosed() throws Exception {
        Stubs.get("identity-access").respond((r, n) -> new Reply(401, Map.of("X-Internal-Auth", "rejected", "Content-Type", "application/problem+json"), "{}", 0));
        HttpResponse<String> r = call("GET", SPACE + "/transactions", h("Cookie", COOKIE + "=good"), null);
        assertThat(r.statusCode()).isEqualTo(502);
        assertThat(Stubs.get("finance").callCount()).isZero();
    }

    @Test
    void anOptionalSessionRouteStillGetsAnAnonymousTokenForAnInvalidCookie() throws Exception {
        call("POST", "/v1/auth/login", h("Cookie", COOKIE + "=stale", "Content-Type", "application/json"), "{}");
        StubServer.Received forwarded = Stubs.get("identity-access").received().get(1);
        assertThat(forwarded.path()).isEqualTo("/v1/auth/login");
        assertThat(verify(forwarded.headers().get("Authorization"), "identity-access").kind()).isEqualTo(PrincipalKind.ANONYMOUS);
    }

    @Test
    void aLiveSessionOnLoginIsForwardedSoItCanBeRotated() throws Exception {
        call("POST", "/v1/auth/login", h("Cookie", COOKIE + "=good", "Content-Type", "application/json"), "{}");
        StubServer.Received forwarded = Stubs.get("identity-access").received().get(1);
        InternalPrincipal p = verify(forwarded.headers().get("Authorization"), "identity-access");
        assertThat(p.kind()).isEqualTo(PrincipalKind.USER);
        assertThat(p.sessionId()).isEqualTo(SESSION);
    }

    // ---- forged identity ----

    @Test
    void forgedIdentityAndWorkspaceHeadersAreIgnored() throws Exception {
        UUID victim = UUID.randomUUID();
        call("GET", "/v1/me", h("Cookie", COOKIE + "=good", "X-User-Id", victim.toString(), "X-Tenant-Id", victim.toString(),
                "X-Workspace-Id", victim.toString(), "X-User-Role", "OWNER", "Authorization", "Bearer forged"), null);
        StubServer.Received forwarded = Stubs.get("identity-access").received().get(1);
        assertThat(forwarded.headers()).doesNotContainKeys("X-User-Id", "X-Tenant-Id", "X-Workspace-Id", "X-User-Role");
        InternalPrincipal p = verify(forwarded.headers().get("Authorization"), "identity-access");
        assertThat(p.subject()).isEqualTo(USER.toString());
        assertThat(p.tenantId()).isNull();
    }

    @Test
    void aSessionHasNoWorkspaceAccessUntilMembershipsAreEstablished() throws Exception {
        // Workspace membership is Step 16; until then a valid session must not reach any workspace path.
        HttpResponse<String> r = call("GET", SPACE + "/transactions", h("Cookie", COOKIE + "=good"), null);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(Stubs.get("finance").callCount()).isZero();
    }

    // ---- CSRF and origin ----

    @Test
    void stateChangingRequestsOnProtectedRoutesNeedTheSessionsCsrfToken() throws Exception {
        Map<String, String> base = h("Cookie", COOKIE + "=good", "Content-Type", "application/json");
        HttpResponse<String> missing = call("POST", "/v1/auth/logout", base, "{}");
        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(missing.body()).contains("CSRF_INVALID");
        Map<String, String> wrong = new LinkedHashMap<>(base);
        wrong.put("X-CSRF-Token", "csrf-wrong");
        assertThat(call("POST", "/v1/auth/logout", wrong, "{}").statusCode()).isEqualTo(403);
        assertThat(Stubs.get("identity-access").received()).noneMatch(r -> r.path().equals("/v1/auth/logout"));

        Map<String, String> good = new LinkedHashMap<>(base);
        good.put("X-CSRF-Token", "csrf-1");
        assertThat(call("POST", "/v1/auth/logout", good, "{}").statusCode()).isEqualTo(200);
        StubServer.Received forwarded = Stubs.get("identity-access").received().stream()
                .filter(r -> r.path().equals("/v1/auth/logout")).findFirst().orElseThrow();
        assertThat(forwarded.headers()).doesNotContainKey("X-CSRF-Token").doesNotContainKey("Cookie");
    }

    @Test
    void readsDoNotNeedACsrfToken() throws Exception {
        assertThat(call("GET", "/v1/me", h("Cookie", COOKIE + "=good"), null).statusCode()).isEqualTo(200);
    }

    @Test
    void crossOriginStateChangesAreRefusedEvenWithAValidCsrfToken() throws Exception {
        Map<String, String> evil = h("Cookie", COOKIE + "=good", "X-CSRF-Token", "csrf-1", "Content-Type", "application/json",
                "Origin", "https://evil.example");
        assertThat(call("POST", "/v1/auth/logout", evil, "{}").statusCode()).isEqualTo(403);
        assertThat(Stubs.get("identity-access").received()).noneMatch(r -> r.path().equals("/v1/auth/logout"));
        // Login is also protected against cross-site form posts.
        assertThat(call("POST", "/v1/auth/login", h("Origin", "https://evil.example", "Content-Type", "application/json"), "{}").statusCode()).isEqualTo(403);
        Map<String, String> allowed = h("Cookie", COOKIE + "=good", "X-CSRF-Token", "csrf-1", "Content-Type", "application/json",
                "Origin", "https://app.example.test");
        assertThat(call("POST", "/v1/auth/logout", allowed, "{}").statusCode()).isEqualTo(200);
    }

    @Test
    void publicCredentialRoutesNeedNeitherSessionNorCsrf() throws Exception {
        assertThat(call("POST", "/v1/auth/register", h("Content-Type", "application/json"), "{}").statusCode()).isEqualTo(200);
        assertThat(call("POST", "/v1/auth/password-reset/request", h("Content-Type", "application/json"), "{}").statusCode()).isEqualTo(200);
    }

    @Test
    void logoutAndPasswordChangeRequireASession() throws Exception {
        assertThat(call("POST", "/v1/auth/logout", h("Content-Type", "application/json"), "{}").statusCode()).isEqualTo(401);
        assertThat(call("POST", "/v1/auth/password/change", h("Content-Type", "application/json"), "{}").statusCode()).isEqualTo(401);
    }

    @Test
    void internalSessionEndpointIsNotReachableFromOutside() throws Exception {
        assertThat(call("POST", "/internal/v1/sessions/validate", h("Content-Type", "application/json"), "{\"session_token\":\"good\"}").statusCode())
                .isEqualTo(404);
        assertThat(Stubs.get("identity-access").callCount()).isZero();
    }
}
