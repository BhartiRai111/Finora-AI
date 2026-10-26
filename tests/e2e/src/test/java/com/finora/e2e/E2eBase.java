package com.finora.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.testkit.TestKeys;
import com.finora.testkit.TestTokens;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One shared stack for all end-to-end tests: the packaged gateway and identity-access jars as separate JVMs, started once
 * and stopped at JVM exit. Only the gateway's public port is used by tests.
 */
abstract class E2eBase {

    static final String PASSWORD = "e2e-Correct-Horse-42-battery";
    static final String COOKIE = "__Host-finora_sid";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final List<String> SECRETS = new ArrayList<>();

    static Process gateway;
    static Process identity;
    static Path logDir;
    static Path devOutbox;
    static int gatewayPort;

    @org.junit.jupiter.api.BeforeAll
    static synchronized void startStack() throws Exception {
        if (gateway != null) {
            return;
        }
        Path root = findRoot();
        Path gatewayJar = jar(root, "api-gateway");
        Path identityJar = jar(root, "identity-access");
        logDir = Files.createTempDirectory("finora-e2e-logs");
        devOutbox = logDir.resolve("invitations.jsonl");
        Path mfaKey = logDir.resolve("mfa.key");
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Files.writeString(mfaKey, Base64.getEncoder().encodeToString(key));
        Path keys = TestKeys.exportPair(TestTokens.GATEWAY);
        gatewayPort = freePort();
        int identityPort = freePort();
        Map<String, String> common = Map.of("FINORA_KEYS_DIR", keys.toString(), "FINORA_COOKIE_SECURE", "true");

        Map<String, String> identityEnv = new LinkedHashMap<>(common);
        identityEnv.put("SERVER_PORT", String.valueOf(identityPort));
        identityEnv.put("FINORA_AUTH_IDLE_TIMEOUT", "6s");
        identityEnv.put("FINORA_AUTH_TOUCH_INTERVAL", "1s");
        identityEnv.put("FINORA_MFA_KEY_PATH", mfaKey.toString());
        identityEnv.put("FINORA_INVITATIONS_DEV_OUTBOX_FILE", devOutbox.toString());
        identity = launch(identityJar, identityEnv, logDir.resolve("identity-access.log"));

        Map<String, String> gatewayEnv = new LinkedHashMap<>(common);
        gatewayEnv.put("SERVER_PORT", String.valueOf(gatewayPort));
        gatewayEnv.put("IDENTITY_ACCESS_URL", "http://127.0.0.1:" + identityPort);
        gatewayEnv.put("FINORA_GATEWAY_AUTH_CAPACITY", "500");
        gatewayEnv.put("FINORA_ALLOWED_ORIGINS", "https://app.example.test");
        gateway = launch(gatewayJar, gatewayEnv, logDir.resolve("api-gateway.log"));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process p : new Process[] {gateway, identity}) {
                if (p != null) {
                    p.destroy();
                }
            }
        }));
        awaitReady(identityPort);
        awaitReady(gatewayPort);
    }


    record Resp(int status, JsonNode body, HttpResponse<String> raw) {
    }

    record Login(String token, String csrf, String userId, String setCookie) {
    }

    static Login registerAndLogin(String email) throws Exception {
        assertThat(post("/v1/auth/register", null, null, Map.of("email", email, "password", PASSWORD, "display_name", "E2E User")).status())
                .isEqualTo(201);
        return login(email, PASSWORD);
    }

    static Login login(String email, String password) throws Exception {
        Resp r = post("/v1/auth/login", null, null, Map.of("email", email, "password", password));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        String setCookie = r.raw().headers().firstValue("Set-Cookie").orElseThrow();
        String token = cookieValue(setCookie);
        String csrf = r.body().path("csrf_token").asString();
        SECRETS.add(token);
        SECRETS.add(csrf);
        return new Login(token, csrf, r.body().path("user").path("id").asString(), setCookie);
    }

    static String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    static String email() {
        return "e2e-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    static Resp get(String path, String sessionToken, Map<String, String> headers) throws Exception {
        return send("GET", path, sessionToken, headers, null);
    }

    static Resp delete(String path, String sessionToken, Map<String, String> headers) throws Exception {
        return send("DELETE", path, sessionToken, headers, null);
    }

    static Resp post(String path, String sessionToken, Map<String, String> headers, Object body) throws Exception {
        return send("POST", path, sessionToken, headers, body == null ? "{}" : JSON.writeValueAsString(body));
    }

    static Resp send(String method, String path, String sessionToken, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (sessionToken != null) {
            b.header("Cookie", COOKIE + "=" + sessionToken);
        }
        if (headers != null) {
            headers.forEach(b::header);
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(r.body()), r);
    }

    static Process launch(Path jar, Map<String, String> env, Path log) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder pb = new ProcessBuilder(java.toString(), "-jar", jar.toString());
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        return pb.start();
    }

    static void awaitReady(int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness"))
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // not up yet
            }
            Thread.sleep(300);
        }
        throw new IllegalStateException("service on port " + port + " did not become ready; see logs in " + logDir);
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static Path findRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("services/api-gateway"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("repository root not found");
        }
        return p;
    }

    static Path jar(Path root, String service) throws IOException {
        Path dir = root.resolve("services").resolve(service).resolve("target");
        Path jar = dir.resolve(service + "-0.1.0-SNAPSHOT.jar");
        if (!Files.exists(jar)) {
            throw new IllegalStateException(jar + " is missing: run ./mvnw verify (the e2e module needs the packaged jars)");
        }
        return jar;
    }
}
