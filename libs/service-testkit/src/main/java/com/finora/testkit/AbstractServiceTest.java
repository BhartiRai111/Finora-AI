package com.finora.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Checks every service must pass: liveness and readiness probes are up, health output does not leak
 * details, and sensitive actuator endpoints are not exposed. Runs against a real embedded server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractServiceTest {

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected Environment env;

    /** Every service trusts the throw-away gateway key and one throw-away caller key in tests. */
    @DynamicPropertySource
    static void internalAuthKeys(DynamicPropertyRegistry registry) {
        registry.add("finora.security.internal-token.trusted-issuers." + TestTokens.GATEWAY + ".public-key-path",
                () -> TestKeys.publicPem(TestTokens.GATEWAY).toString());
        registry.add("finora.security.internal-token.trusted-issuers." + TestTokens.CALLER + ".public-key-path",
                () -> TestKeys.publicPem(TestTokens.CALLER).toString());
    }

    protected HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, Map.of(), null);
    }

    protected HttpResponse<String> send(String method, String path, Map<String, String> headers, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void livenessProbeIsUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health/liveness");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void readinessProbeIsUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void healthResponseDoesNotLeakComponentDetails() throws Exception {
        HttpResponse<String> response = get("/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("components").doesNotContain("jdbc").doesNotContain("url");
    }

    @Test
    void sensitiveActuatorEndpointsAreNotExposed() throws Exception {
        for (String path : new String[] {"/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/configprops"}) {
            // 404 (not exposed) or 401 (the internal-token filter answers first); never a 200.
            assertThat(get(path).statusCode()).as(path).isIn(401, 404);
        }
    }

    @Test
    void validRequestIdIsEchoedAndInvalidOneIsReplaced() throws Exception {
        String good = "7f1c2a9e-3b4d-4c5e-8f60-123456789abc";
        assertThat(send("GET", "/actuator/health", Map.of("X-Request-Id", good), null).headers()
                .firstValue("X-Request-Id")).contains(good);
        String bad = send("GET", "/actuator/health", Map.of("X-Request-Id", "../../etc/passwd"), null).headers()
                .firstValue("X-Request-Id").orElseThrow();
        assertThat(bad).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        HttpResponse<String> response = get("/actuator/health");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void serverIsBoundToLoopbackByDefault() {
        assertThat(env.getProperty("server.address")).isEqualTo("127.0.0.1");
    }
}
