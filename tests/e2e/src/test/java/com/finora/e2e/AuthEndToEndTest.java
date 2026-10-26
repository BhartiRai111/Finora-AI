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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real processes, real HTTP, real PostgreSQL, real sessions: the packaged gateway and identity-access jars are started
 * as separate JVMs and exercised only through the gateway's public port. Nothing in this test fakes authentication.
 * Requires FINORA_DEV_DB_PASSWORD / FINORA_DB_HOST / FINORA_DB_PORT in the environment (as the other service tests do).
 */
class AuthEndToEndTest {

    private static final String PASSWORD = "e2e-Correct-Horse-42-battery";
    private static final String NEW_PASSWORD = "e2e-Another-Staple-77-zebra";
    private static final String COOKIE = "__Host-finora_sid";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final List<String> SECRETS = new ArrayList<>();

    private static Process gateway;
    private static Process identity;
    private static Path logDir;
    private static int gatewayPort;

    @BeforeAll
    static void start() throws Exception {
        Path root = findRoot();
        Path gatewayJar = jar(root, "api-gateway");
        Path identityJar = jar(root, "identity-access");
        logDir = Files.createTempDirectory("finora-e2e-logs");
        Path keys = TestKeys.exportPair(TestTokens.GATEWAY);
        gatewayPort = freePort();
        int identityPort = freePort();
        Map<String, String> common = Map.of("FINORA_KEYS_DIR", keys.toString(), "FINORA_COOKIE_SECURE", "true");

        Map<String, String> identityEnv = new LinkedHashMap<>(common);
        identityEnv.put("SERVER_PORT", String.valueOf(identityPort));
        identityEnv.put("FINORA_AUTH_IDLE_TIMEOUT", "6s");
        identityEnv.put("FINORA_AUTH_TOUCH_INTERVAL", "1s");
        identity = launch(identityJar, identityEnv, logDir.resolve("identity-access.log"));

        Map<String, String> gatewayEnv = new LinkedHashMap<>(common);
        gatewayEnv.put("SERVER_PORT", String.valueOf(gatewayPort));
        gatewayEnv.put("IDENTITY_ACCESS_URL", "http://127.0.0.1:" + identityPort);
        gatewayEnv.put("FINORA_GATEWAY_AUTH_CAPACITY", "500");
        gatewayEnv.put("FINORA_ALLOWED_ORIGINS", "https://app.example.test");
        gateway = launch(gatewayJar, gatewayEnv, logDir.resolve("api-gateway.log"));

        awaitReady(identityPort);
        awaitReady(gatewayPort);
    }

    @AfterAll
    static void stop() throws Exception {
        for (Process p : new Process[] {gateway, identity}) {
            if (p != null) {
                p.destroy();
                if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            }
        }
    }

    // ---- scenarios ----

    @Test
    void registerLoginReadProfileThroughTheGateway() throws Exception {
        String email = email();
        assertThat(post("/v1/auth/register", null, null, Map.of("email", email, "password", PASSWORD, "display_name", "E2E User")).status())
                .isEqualTo(201);
        Login l = login(email, PASSWORD);

        assertThat(l.setCookie).startsWith(COOKIE + "=").contains("HttpOnly").contains("Secure").contains("SameSite=Lax").contains("Path=/");
        Resp me = get("/v1/me", l.token, null);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body.path("email").asString()).isEqualTo(email);
        assertThat(me.raw.body()).doesNotContain(l.token).doesNotContain(PASSWORD);

        assertThat(get("/v1/me", null, null).status()).as("no cookie").isEqualTo(401);
        assertThat(get("/v1/me", "garbage-token", null).status()).as("invalid cookie").isEqualTo(401);
        assertThat(me.raw.headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    void forgedIdentityHeadersAreIgnoredAndCannotAuthenticate() throws Exception {
        String emailA = email();
        Login a = registerAndLogin(emailA);
        Login b = registerAndLogin(email());
        Map<String, String> forged = Map.of("X-User-Id", UUID.randomUUID().toString(), "X-Tenant-Id", UUID.randomUUID().toString(),
                "X-User-Role", "OWNER", "X-Forwarded-For", "10.9.8.7");
        assertThat(get("/v1/me", null, forged).status()).isEqualTo(401);
        Resp asA = get("/v1/me", a.token, forged);
        assertThat(asA.status()).isEqualTo(200);
        assertThat(asA.body.path("email").asString()).isEqualTo(emailA);
        assertThat(asA.body.path("id").asString()).isNotEqualTo(b.userId);
        assertThat(get("/v1/me", null, Map.of("Authorization", "Bearer " + TestTokens.gatewayUser("identity-access", null))).status())
                .as("a hand-made internal token is not a session").isEqualTo(401);
    }

    @Test
    void internalEndpointsAreNotReachableThroughTheGateway() throws Exception {
        Login l = registerAndLogin(email());
        assertThat(post("/internal/v1/sessions/validate", null, null, Map.of("session_token", l.token)).status()).isEqualTo(404);
        assertThat(get("/internal/v1/ping", l.token, null).status()).isEqualTo(404);
    }

    @Test
    void logoutNeedsCsrfAndAllowedOriginThenRevokesTheSession() throws Exception {
        Login l = registerAndLogin(email());
        assertThat(post("/v1/auth/logout", l.token, null, null).status()).as("no CSRF token").isEqualTo(403);
        assertThat(post("/v1/auth/logout", l.token, Map.of("X-CSRF-Token", "wrong"), null).status()).isEqualTo(403);
        assertThat(post("/v1/auth/logout", l.token, Map.of("X-CSRF-Token", l.csrf, "Origin", "https://evil.example"), null).status())
                .as("cross-site origin").isEqualTo(403);
        assertThat(get("/v1/me", l.token, null).status()).as("still logged in after refused attempts").isEqualTo(200);

        Resp out = post("/v1/auth/logout", l.token, Map.of("X-CSRF-Token", l.csrf, "Origin", "https://app.example.test"), null);
        assertThat(out.status()).isEqualTo(204);
        assertThat(out.raw.headers().firstValue("Set-Cookie").orElse("")).contains("Max-Age=0");
        assertThat(get("/v1/me", l.token, null).status()).as("revoked session").isEqualTo(401);
    }

    @Test
    void loggingInAgainRotatesTheSession() throws Exception {
        String email = email();
        Login first = registerAndLogin(email);
        Resp second = post("/v1/auth/login", first.token, null, Map.of("email", email, "password", PASSWORD));
        assertThat(second.status()).isEqualTo(200);
        String newToken = cookieValue(second.raw.headers().firstValue("Set-Cookie").orElseThrow());
        SECRETS.add(newToken);
        assertThat(newToken).isNotEqualTo(first.token);
        assertThat(get("/v1/me", first.token, null).status()).as("old session revoked").isEqualTo(401);
        assertThat(get("/v1/me", newToken, null).status()).isEqualTo(200);
    }

    @Test
    void changingThePasswordSignsOutEveryDevice() throws Exception {
        String email = email();
        Login one = registerAndLogin(email);
        Login two = login(email, PASSWORD);
        Resp r = post("/v1/auth/password/change", one.token, Map.of("X-CSRF-Token", one.csrf),
                Map.of("current_password", PASSWORD, "new_password", NEW_PASSWORD));
        assertThat(r.status()).isEqualTo(200);
        String fresh = cookieValue(r.raw.headers().firstValue("Set-Cookie").orElseThrow());
        SECRETS.add(fresh);
        assertThat(get("/v1/me", one.token, null).status()).isEqualTo(401);
        assertThat(get("/v1/me", two.token, null).status()).isEqualTo(401);
        assertThat(get("/v1/me", fresh, null).status()).isEqualTo(200);
        assertThat(post("/v1/auth/login", null, null, Map.of("email", email, "password", PASSWORD)).status()).isEqualTo(401);
        assertThat(post("/v1/auth/login", null, null, Map.of("email", email, "password", NEW_PASSWORD)).status()).isEqualTo(200);
    }

    @Test
    void sessionExpiresAfterTheIdleTimeout() throws Exception {
        Login l = registerAndLogin(email());
        assertThat(get("/v1/me", l.token, null).status()).isEqualTo(200);
        Thread.sleep(7500);
        assertThat(get("/v1/me", l.token, null).status()).as("idle timeout is 6s in this run").isEqualTo(401);
    }

    @Test
    void repeatedBadLoginsAreThrottledThroughTheGateway() throws Exception {
        String email = email();
        post("/v1/auth/register", null, null, Map.of("email", email, "password", PASSWORD, "display_name", "Lock Test"));
        for (int i = 0; i < 5; i++) {
            assertThat(post("/v1/auth/login", null, null, Map.of("email", email, "password", "wrong-guess-" + i + "-xx")).status()).isEqualTo(401);
        }
        Resp locked = post("/v1/auth/login", null, null, Map.of("email", email, "password", PASSWORD));
        assertThat(locked.status()).isEqualTo(429);
        assertThat(locked.raw.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void registrationErrorsAreSafe() throws Exception {
        String email = email();
        assertThat(post("/v1/auth/register", null, null, Map.of("email", email, "password", "short", "display_name", "X")).status()).isEqualTo(422);
        assertThat(post("/v1/auth/register", null, null, Map.of("email", email, "password", PASSWORD, "display_name", "X")).status()).isEqualTo(201);
        Resp dup = post("/v1/auth/register", null, null, Map.of("email", email, "password", PASSWORD, "display_name", "X"));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.raw.body()).doesNotContain("Exception").doesNotContain("users_email").doesNotContain("postgres");
    }

    @Test
    void passwordResetAcceptsTheRequestButHonestlyCannotDeliverWithoutAMailProvider() throws Exception {
        String email = email();
        registerAndLogin(email);
        Resp r = post("/v1/auth/password-reset/request", null, null, Map.of("email", email));
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.raw.body()).doesNotContain("token");
        Thread.sleep(500);
        assertThat(Files.readString(logDir.resolve("identity-access.log"))).contains("email delivery is NOT configured");
    }

    @Test
    void zzNoSecretEverReachedTheLogs() throws Exception {
        // Runs last by name only as a convenience; the check is valid whenever it runs because SECRETS only grows.
        Thread.sleep(300);
        String all = Files.readString(logDir.resolve("identity-access.log")) + Files.readString(logDir.resolve("api-gateway.log"));
        assertThat(SECRETS).isNotEmpty();
        for (String secret : SECRETS) {
            assertThat(all).as("a secret appeared in a log").doesNotContain(secret);
        }
        assertThat(all).doesNotContain(PASSWORD).doesNotContain(NEW_PASSWORD);
    }

    // ---- helpers ----

    record Resp(int status, JsonNode body, HttpResponse<String> raw) {
    }

    record Login(String token, String csrf, String userId, String setCookie) {
    }

    private static Login registerAndLogin(String email) throws Exception {
        assertThat(post("/v1/auth/register", null, null, Map.of("email", email, "password", PASSWORD, "display_name", "E2E User")).status())
                .isEqualTo(201);
        return login(email, PASSWORD);
    }

    private static Login login(String email, String password) throws Exception {
        Resp r = post("/v1/auth/login", null, null, Map.of("email", email, "password", password));
        assertThat(r.status()).as(r.raw.body()).isEqualTo(200);
        String setCookie = r.raw.headers().firstValue("Set-Cookie").orElseThrow();
        String token = cookieValue(setCookie);
        String csrf = r.body.path("csrf_token").asString();
        SECRETS.add(token);
        SECRETS.add(csrf);
        return new Login(token, csrf, r.body.path("user").path("id").asString(), setCookie);
    }

    private static String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    private static String email() {
        return "e2e-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    private static Resp get(String path, String sessionToken, Map<String, String> headers) throws Exception {
        return send("GET", path, sessionToken, headers, null);
    }

    private static Resp post(String path, String sessionToken, Map<String, String> headers, Object body) throws Exception {
        return send("POST", path, sessionToken, headers, body == null ? "{}" : JSON.writeValueAsString(body));
    }

    private static Resp send(String method, String path, String sessionToken, Map<String, String> headers, String body) throws Exception {
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

    private static Process launch(Path jar, Map<String, String> env, Path log) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder pb = new ProcessBuilder(java.toString(), "-jar", jar.toString());
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        return pb.start();
    }

    private static void awaitReady(int port) throws Exception {
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

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Path findRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("services/api-gateway"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("repository root not found");
        }
        return p;
    }

    private static Path jar(Path root, String service) throws IOException {
        Path dir = root.resolve("services").resolve(service).resolve("target");
        Path jar = dir.resolve(service + "-0.1.0-SNAPSHOT.jar");
        if (!Files.exists(jar)) {
            throw new IllegalStateException(jar + " is missing: run ./mvnw verify (the e2e module needs the packaged jars)");
        }
        return jar;
    }
}
