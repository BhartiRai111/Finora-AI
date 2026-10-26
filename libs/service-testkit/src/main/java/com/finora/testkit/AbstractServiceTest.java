package com.finora.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

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

    protected HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
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
            assertThat(get(path).statusCode()).as(path).isEqualTo(404);
        }
    }

    @Test
    void serverIsBoundToLoopbackByDefault() {
        assertThat(env.getProperty("server.address")).isEqualTo("127.0.0.1");
    }
}
