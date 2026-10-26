package com.finora.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.testkit.TestKeys;
import com.finora.testkit.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "finora.gateway.rate-limit.enabled=true",
        "finora.gateway.rate-limit.capacity=3",
        "finora.gateway.rate-limit.refill-per-second=0.001",
        "finora.gateway.rate-limit.auth-capacity=2",
        "finora.gateway.rate-limit.auth-refill-per-second=0.001"})
@Import(GatewayProxyTest.TestSession.class)
class GatewayRateLimitTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String SPACE = "/v1/spaces/" + HeaderTestSessionAuthenticator.SPACE;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Stubs.register(registry);
        registry.add("finora.security.internal-token.signing.private-key-path",
                () -> TestKeys.privatePem(TestTokens.GATEWAY).toString());
    }

    @Value("${local.server.port}")
    int port;

    private HttpResponse<String> call(String method, String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody());
        headers.forEach(b::header);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void userIsLimitedAfterTheirBurstAndTheDeniedRequestNeverReachesTheService() throws Exception {
        Stubs.resetAll();
        Map<String, String> member = Map.of("X-Test-Session", "member");
        for (int i = 0; i < 3; i++) {
            assertThat(call("GET", SPACE + "/budgets", member).statusCode()).isEqualTo(200);
        }
        HttpResponse<String> limited = call("GET", SPACE + "/budgets", member);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(limited.body()).contains("RATE_LIMITED");
        assertThat(Stubs.get("finance").callCount()).isEqualTo(3);
    }

    @Test
    void credentialEndpointsHaveAStricterLimitKeyedOnTheClientAddressNotAForgeableHeader() throws Exception {
        Stubs.resetAll();
        Map<String, String> json = Map.of("Content-Type", "application/json");
        assertThat(call("POST", "/v1/auth/login", json).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/v1/auth/login", Map.of("Content-Type", "application/json", "X-Forwarded-For", "203.0.113.9")).statusCode()).isEqualTo(200);
        // Spoofing X-Forwarded-For does not buy a fresh bucket.
        assertThat(call("POST", "/v1/auth/login", Map.of("Content-Type", "application/json", "X-Forwarded-For", "203.0.113.10")).statusCode()).isEqualTo(429);
    }
}
