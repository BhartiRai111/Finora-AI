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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end through the real gateway process-in-test: real HTTP in, real HTTP out to loopback stubs that play the
 * private services. Failure scenarios use distinct downstream services so one scenario's circuit state cannot leak
 * into another's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "finora.gateway.read-timeout=700ms",
        "finora.gateway.write-timeout=700ms",
        "finora.gateway.max-retries=2",
        "finora.gateway.max-body-bytes=2048",
        "finora.gateway.rate-limit.enabled=false"})
@Import(GatewayProxyTest.TestSession.class)
class GatewayProxyTest {

    @TestConfiguration
    static class TestSession {
        @Bean
        @Primary
        SessionAuthenticator testSessionAuthenticator() {
            return new HeaderTestSessionAuthenticator();
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String SPACE = "/v1/spaces/" + HeaderTestSessionAuthenticator.SPACE;
    private static final Map<String, String> MEMBER = Map.of("X-Test-Session", "member");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Stubs.registerWithDown(registry, "intelligence");
        registry.add("finora.security.internal-token.signing.private-key-path",
                () -> TestKeys.privatePem(TestTokens.GATEWAY).toString());
    }

    @Value("${local.server.port}")
    int port;

    @BeforeEach
    void reset() {
        Stubs.resetAll();
    }

    private HttpResponse<String> call(String method, String path, Map<String, String> headers, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> with(Map<String, String> base, String... kv) {
        Map<String, String> m = new LinkedHashMap<>(base);
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private InternalPrincipal verify(String authorizationHeader, String audience) throws Exception {
        InternalTokenVerifier verifier = new InternalTokenVerifier(audience, TestTokens.GATEWAY,
                Map.of(TestTokens.GATEWAY, TestKeys.publicKey(TestTokens.GATEWAY)), Duration.ofMinutes(10),
                Duration.ofSeconds(30), Clock.systemUTC());
        assertThat(authorizationHeader).startsWith("Bearer ");
        return verifier.verify(authorizationHeader.substring(7));
    }

    // ---- authentication and token minting ----

    @Test
    void sessionRequiredRouteWithoutASessionIsRefusedAndNotForwarded() throws Exception {
        HttpResponse<String> response = call("GET", SPACE + "/transactions", Map.of(), null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(response.body()).contains("AUTH_REQUIRED").contains("request_id");
        assertThat(Stubs.get("finance").callCount()).isZero();
    }

    @Test
    void authenticatedRequestReachesOnlyTheOwningServiceWithAScopedShortLivedToken() throws Exception {
        HttpResponse<String> response = call("GET", SPACE + "/transactions?limit=5", MEMBER, null);
        assertThat(response.statusCode()).isEqualTo(200);
        StubServer finance = Stubs.get("finance");
        assertThat(finance.received()).hasSize(1);
        StubServer.Received received = finance.received().get(0);
        assertThat(received.path()).isEqualTo(SPACE + "/transactions?limit=5");
        InternalPrincipal principal = verify(received.headers().get("Authorization"), "finance");
        assertThat(principal.kind()).isEqualTo(PrincipalKind.USER);
        assertThat(principal.subject()).isEqualTo(HeaderTestSessionAuthenticator.USER.toString());
        assertThat(principal.tenantId()).isEqualTo(HeaderTestSessionAuthenticator.SPACE);
        assertThat(principal.role()).isEqualTo("OWNER");
        assertThat(principal.expiresAt()).isBefore(java.time.Instant.now().plusSeconds(150));
        for (String other : Stubs.SERVICES) {
            if (!other.equals("finance")) {
                assertThat(Stubs.get(other).callCount()).as(other).isZero();
            }
        }
    }

    @Test
    void tokenIsOnlyValidForTheOwningServiceAudience() throws Exception {
        call("GET", SPACE + "/transactions", MEMBER, null);
        String header = Stubs.get("finance").received().get(0).headers().get("Authorization");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> verify(header, "document"))
                .isInstanceOf(com.finora.commons.security.InternalAuthException.class);
    }

    @Test
    void userCannotReachAWorkspaceTheyAreNotAMemberOf() throws Exception {
        HttpResponse<String> response = call("GET", "/v1/spaces/99999999-9999-4999-8999-999999999999/transactions", MEMBER, null);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(Stubs.get("finance").callCount()).isZero();
    }

    @Test
    void malformedSpaceIdIsTreatedAsNotFound() throws Exception {
        assertThat(call("GET", "/v1/spaces/not-a-uuid/transactions", MEMBER, null).statusCode()).isEqualTo(404);
        assertThat(Stubs.get("finance").callCount()).isZero();
    }

    @Test
    void anonymousCallerGetsAnAnonymousTokenOnOptionalSessionRoutes() throws Exception {
        call("POST", "/v1/auth/login", Map.of("Content-Type", "application/json"), "{}");
        InternalPrincipal principal = verify(Stubs.get("identity-access").received().get(0).headers().get("Authorization"),
                "identity-access");
        assertThat(principal.kind()).isEqualTo(PrincipalKind.ANONYMOUS);
        assertThat(principal.tenantId()).isNull();
    }

    // ---- spoofing and header hygiene ----

    @Test
    void clientSuppliedIdentityHeadersNeverReachTheService() throws Exception {
        Map<String, String> hostile = with(MEMBER,
                "Authorization", "Bearer forged.token.value",
                "Cookie", "session=stolen",
                "X-Forwarded-For", "10.0.0.1",
                "X-Tenant-Id", "99999999-9999-4999-8999-999999999999",
                "X-User-Role", "ADMIN",
                "X-Internal-Auth", "ok");
        call("GET", SPACE + "/transactions", hostile, null);
        Map<String, String> seen = Stubs.get("finance").received().get(0).headers();
        assertThat(seen).doesNotContainKeys("Cookie", "X-Forwarded-For", "X-Tenant-Id", "X-User-Role", "X-Internal-Auth",
                "X-Test-Session");
        assertThat(seen.get("Authorization")).doesNotContain("forged");
        assertThat(verify(seen.get("Authorization"), "finance").tenantId()).isEqualTo(HeaderTestSessionAuthenticator.SPACE);
    }

    @Test
    void allowlistedHeadersAreForwardedUnchanged() throws Exception {
        call("PUT", SPACE + "/transactions/1", with(MEMBER, "Content-Type", "application/json", "If-Match", "\"v3\"",
                "Idempotency-Key", "abc-123", "Accept-Language", "en-IN"), "{}");
        Map<String, String> seen = Stubs.get("finance").received().get(0).headers();
        assertThat(seen.get("If-Match")).isEqualTo("\"v3\"");
        assertThat(seen.get("Idempotency-Key")).isEqualTo("abc-123");
        assertThat(seen.get("Accept-Language")).isEqualTo("en-IN");
    }

    @Test
    void internalHeadersFromTheServiceAreNotLeakedToTheClient() throws Exception {
        Stubs.get("document").respond((r, n) -> new Reply(200, Map.of("Content-Type", "application/json",
                "ETag", "\"abc\"", "Server", "internal-svc/9.9", "X-Internal-Secret", "s3cr3t", "Set-Cookie", "x=1"), "{}", 0));
        HttpResponse<String> response = call("GET", SPACE + "/documents", MEMBER, null);
        assertThat(response.headers().firstValue("ETag")).contains("\"abc\"");
        assertThat(response.headers().firstValue("X-Internal-Secret")).isEmpty();
        assertThat(response.headers().firstValue("Server")).isEmpty();
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void onlyTheAuthRoutesMaySetTheSessionCookie() throws Exception {
        Stubs.get("identity-access").respond((r, n) -> new Reply(200, Map.of("Content-Type", "application/json",
                "Set-Cookie", "sid=opaque; HttpOnly; Secure; SameSite=Lax"), "{}", 0));
        HttpResponse<String> login = call("POST", "/v1/auth/login", Map.of("Content-Type", "application/json"), "{}");
        assertThat(login.headers().firstValue("Set-Cookie").orElse("")).contains("HttpOnly");
        HttpResponse<String> me = call("GET", "/v1/me", MEMBER, null);
        assertThat(me.headers().firstValue("Set-Cookie")).isEmpty();
    }

    // ---- correlation ----

    @Test
    void requestIdAndTraceContextAreCarriedToTheServiceAndBack() throws Exception {
        String id = "7f1c2a9e-3b4d-4c5e-8f60-123456789abc";
        String trace = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        HttpResponse<String> response = call("GET", SPACE + "/transactions", with(MEMBER, "X-Request-Id", id, "traceparent", trace), null);
        Map<String, String> seen = Stubs.get("finance").received().get(0).headers();
        assertThat(seen.get("X-Request-Id")).isEqualTo(id);
        assertThat(seen.get("traceparent")).isEqualTo(trace);
        assertThat(response.headers().firstValue("X-Request-Id")).contains(id);
    }

    @Test
    void invalidCorrelationInputIsReplacedNotForwarded() throws Exception {
        call("GET", SPACE + "/transactions", with(MEMBER, "X-Request-Id", "evil\\injected", "traceparent", "garbage"), null);
        Map<String, String> seen = Stubs.get("finance").received().get(0).headers();
        assertThat(seen.get("X-Request-Id")).matches("^[0-9a-f-]{36}$");
        assertThat(seen.get("traceparent")).matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$");
    }

    // ---- routing ----

    @Test
    void reconciliationPeriodsGoToIngestionWhileOtherAccountPathsGoToFinance() throws Exception {
        call("GET", SPACE + "/accounts/5/reconciliation-periods", MEMBER, null);
        call("GET", SPACE + "/accounts/5", MEMBER, null);
        assertThat(Stubs.get("ingestion-integration").received()).hasSize(1);
        assertThat(Stubs.get("finance").received()).hasSize(1);
        assertThat(Stubs.get("finance").received().get(0).path()).isEqualTo(SPACE + "/accounts/5");
    }

    @Test
    void internalAdminAndUnknownPathsAreNotForwarded() throws Exception {
        for (String path : new String[] {"/internal/v1/ping", "/admin/v1/tenants", "/v1/unknown", "/v1/spaces/" + HeaderTestSessionAuthenticator.SPACE + "/internal-things"}) {
            HttpResponse<String> response = call("GET", path, MEMBER, null);
            assertThat(response.statusCode()).as(path).isEqualTo(404);
        }
        Stubs.SERVICES.forEach(s -> assertThat(Stubs.get(s).callCount()).as(s).isZero());
    }

    @Test
    void pathTraversalAndEncodingTricksAreRefused() throws Exception {
        for (String path : new String[] {SPACE + "/transactions/%2e%2e/%2e%2e/admin", SPACE + "/transactions/..%2fx",
                SPACE + "/transactions//x", SPACE + "/transactions/a%00b", SPACE + "/transactions;jsessionid=1"}) {
            int status = call("GET", path, MEMBER, null).statusCode();
            assertThat(status).as(path).isEqualTo(400);
        }
        assertThat(Stubs.get("finance").callCount()).isZero();
    }

    @Test
    void unsupportedMethodsAreRefused() throws Exception {
        assertThat(call("OPTIONS", SPACE + "/transactions", MEMBER, null).statusCode()).isEqualTo(405);
        assertThat(call("TRACE", SPACE + "/transactions", MEMBER, null).statusCode()).isIn(405, 501);
        assertThat(Stubs.get("finance").callCount()).isZero();
    }

    // ---- request shape ----

    @Test
    void oversizedBodyIsRefusedBeforeForwarding() throws Exception {
        HttpResponse<String> response = call("POST", SPACE + "/transactions",
                with(MEMBER, "Content-Type", "application/json"), "x".repeat(4096));
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(Stubs.get("finance").callCount()).isZero();
    }

    @Test
    void unsupportedContentTypeIsRefused() throws Exception {
        HttpResponse<String> response = call("POST", SPACE + "/transactions",
                with(MEMBER, "Content-Type", "text/plain"), "hello");
        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(Stubs.get("finance").callCount()).isZero();
    }

    @Test
    void malformedIdempotencyKeyIsRefused() throws Exception {
        HttpResponse<String> response = call("POST", SPACE + "/transactions",
                with(MEMBER, "Content-Type", "application/json", "Idempotency-Key", "bad key with spaces"), "{}");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Stubs.get("finance").callCount()).isZero();
    }

    @Test
    void uploadRoutesAcceptMultipartButOtherRoutesDoNot() throws Exception {
        assertThat(call("POST", SPACE + "/documents", with(MEMBER, "Content-Type", "multipart/form-data; boundary=x"), "--x--").statusCode())
                .isEqualTo(200);
        assertThat(call("POST", SPACE + "/transactions", with(MEMBER, "Content-Type", "multipart/form-data; boundary=x"), "--x--").statusCode())
                .isEqualTo(415);
    }

    // ---- upstream failure and retry behaviour ----

    @Test
    void upstreamClientErrorsAndBodiesPassThroughUnchanged() throws Exception {
        Stubs.get("platform").respond((r, n) -> Reply.json(409, "{\"code\":\"CONFLICT\",\"request_id\":\"x\"}"));
        HttpResponse<String> response = call("POST", SPACE + "/notifications", with(MEMBER, "Content-Type", "application/json"), "{}");
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("CONFLICT");
        assertThat(Stubs.get("platform").callCount()).isEqualTo(1);
    }

    @Test
    void slowServiceYieldsGatewayTimeoutWithASafeBody() throws Exception {
        Stubs.get("reporting").respond((r, n) -> Reply.json(200, "{}").delayed(3000));
        long start = System.nanoTime();
        HttpResponse<String> response = call("GET", SPACE + "/reports/x", MEMBER, null);
        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.body()).contains("UPSTREAM_TIMEOUT").doesNotContain("reporting").doesNotContain("127.0.0.1");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void unreachableServiceYieldsServiceUnavailableWithRetryAfterAndNoInternalDetail() throws Exception {
        HttpResponse<String> response = call("GET", SPACE + "/ai/chat", MEMBER, null);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
        assertThat(response.body()).contains("UPSTREAM_UNAVAILABLE").doesNotContain("127.0.0.1").doesNotContain("Connection refused")
                .doesNotContain(String.valueOf(Stubs.DEAD_PORT));
    }

    @Test
    void upstreamServerErrorBecomesABadGatewayWithoutLeakingTheServiceBody() throws Exception {
        Stubs.get("audit").respond((r, n) -> Reply.json(500, "{\"trace\":\"java.lang.NullPointerException at com.finora.audit\"}"));
        HttpResponse<String> response = call("GET", SPACE + "/audit/events", MEMBER, null);
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("UPSTREAM_ERROR").doesNotContain("NullPointer").doesNotContain("com.finora");
        assertThat(Stubs.get("audit").callCount()).isEqualTo(1);
    }

    @Test
    void idempotentReadsAreRetriedThroughTheGateway() throws Exception {
        Stubs.get("document").respond((r, n) -> n < 3 ? Reply.json(503, "{}") : Reply.json(200, "{\"ok\":true}"));
        HttpResponse<String> response = call("GET", SPACE + "/documents/1", MEMBER, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Stubs.get("document").callCount()).isEqualTo(3);
    }

    @Test
    void writesWithoutAnIdempotencyKeyAreNotRetriedByTheGateway() throws Exception {
        Stubs.get("finance").respond((r, n) -> Reply.json(503, "{}"));
        HttpResponse<String> response = call("POST", SPACE + "/transactions", with(MEMBER, "Content-Type", "application/json"), "{}");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(Stubs.get("finance").callCount()).isEqualTo(1);
    }

    @Test
    void writesWithAnIdempotencyKeyAreRetriedWithTheSameKey() throws Exception {
        Stubs.get("finance").respond((r, n) -> n == 1 ? Reply.json(503, "{}") : Reply.json(201, "{\"id\":\"1\"}"));
        HttpResponse<String> response = call("POST", SPACE + "/transactions",
                with(MEMBER, "Content-Type", "application/json", "Idempotency-Key", "k-1"), "{}");
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(Stubs.get("finance").received()).hasSize(2)
                .allSatisfy(r -> assertThat(r.headers().get("Idempotency-Key")).isEqualTo("k-1"));
    }

    @Test
    void aServiceThatRejectsTheGatewaysTokenIsAServerSideProblemNotAUserError() throws Exception {
        Stubs.get("identity-access").respond((r, n) -> new Reply(401, Map.of("Content-Type", "application/problem+json",
                "X-Internal-Auth", "rejected"), "{\"code\":\"AUTH_REQUIRED\"}", 0));
        HttpResponse<String> response = call("GET", "/v1/me", MEMBER, null);
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("UPSTREAM_ERROR");
    }

    // ---- gateway's own health ----

    @Test
    void gatewayReadinessIsUp() throws Exception {
        HttpResponse<String> response = call("GET", "/actuator/health/readiness", Map.of(), null);
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void gatewayDoesNotAcceptInternalTokensOrExposeTheInternalPingEndpoint() throws Exception {
        HttpResponse<String> response = call("GET", "/internal/v1/ping",
                Map.of("Authorization", "Bearer " + TestTokens.service("api-gateway")), null);
        assertThat(response.statusCode()).isEqualTo(404);
    }
}
