package com.finora.identityaccess.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.testkit.TestKeys;
import com.finora.testkit.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Authentication against the real service and real PostgreSQL. Calls are made the way the gateway makes them: with
 * verified internal tokens (the service refuses anything else). Time is controlled with a test clock; test-only
 * passwords and throw-away keys are used throughout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthFlowTest.TestBeans.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthFlowTest {

    static final String PASSWORD = "Tr0ub4dor&3-horse-battery";
    static final String NEW_PASSWORD = "correct-Horse-9-staple-!";
    private static final String COOKIE = "__Host-finora_sid";

    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class CapturingNotifier implements PasswordResetNotifier {
        final List<String[]> sent = new ArrayList<>();

        @Override
        public synchronized Outcome deliver(String email, String token, Instant expiresAt) {
            sent.add(new String[] {email, token});
            return Outcome.SENT;
        }

        synchronized String lastTokenFor(String email) {
            for (int i = sent.size() - 1; i >= 0; i--) {
                if (sent.get(i)[0].equalsIgnoreCase(email)) {
                    return sent.get(i)[1];
                }
            }
            return null;
        }

        synchronized int count() {
            return sent.size();
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        CapturingNotifier capturingNotifier() {
            return new CapturingNotifier();
        }
    }

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("finora.security.internal-token.trusted-issuers.gateway.public-key-path",
                () -> TestKeys.publicPem(TestTokens.GATEWAY).toString());
        registry.add("finora.security.internal-token.trusted-issuers.testcaller.public-key-path",
                () -> TestKeys.publicPem(TestTokens.CALLER).toString());
    }

    @Value("${local.server.port}")
    int port;

    @Autowired MutableClock clock;
    @Autowired CapturingNotifier notifier;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    // ---- helpers ----

    record Resp(int status, JsonNode body, HttpResponse<String> raw) {
        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        String code() {
            return body.path("code").asString();
        }
    }

    private String anon() {
        return TestTokens.issuer(TestTokens.GATEWAY, Duration.ofMinutes(2), clock).issueAnonymous("identity-access");
    }

    private String user(UUID userId, UUID sessionId) {
        return TestTokens.issuer(TestTokens.GATEWAY, Duration.ofMinutes(2), clock)
                .issueUser("identity-access", userId, sessionId, null, null, null, List.of(), null);
    }

    private String gatewayService() {
        return TestTokens.issuer(TestTokens.GATEWAY, Duration.ofMinutes(2), clock).issueService("identity-access", null);
    }

    private Resp call(String method, String path, String token, Object body, String... extraHeaders) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : json.writeValueAsString(body)));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        for (int i = 0; i < extraHeaders.length; i += 2) {
            b.header(extraHeaders[i], extraHeaders[i + 1]);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body().isEmpty() ? json.createObjectNode() : json.readTree(r.body()), r);
    }

    private static String email() {
        return "tester-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    private Resp register(String email, String password) throws Exception {
        return call("POST", "/v1/auth/register", anon(), Map.of("email", email, "password", password, "display_name", "Test User"));
    }

    private Resp login(String email, String password) throws Exception {
        return call("POST", "/v1/auth/login", anon(), Map.of("email", email, "password", password));
    }

    record Session(String token, String csrf, UUID sessionId, UUID userId) {
    }

    private Session registerAndLogin(String email) throws Exception {
        assertThat(register(email, PASSWORD).status()).isEqualTo(201);
        return loginSession(email, PASSWORD);
    }

    private Session loginSession(String email, String password) throws Exception {
        Resp r = login(email, password);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        String setCookie = r.header("Set-Cookie");
        String value = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        Resp v = validate(value, r.body().path("csrf_token").asString());
        return new Session(value, r.body().path("csrf_token").asString(), UUID.fromString(v.body().path("session_id").asString()),
                UUID.fromString(r.body().path("user").path("id").asString()));
    }

    private Resp validate(String sessionToken, String csrf) throws Exception {
        Map<String, String> body = csrf == null ? Map.of("session_token", sessionToken)
                : Map.of("session_token", sessionToken, "csrf_token", csrf);
        return call("POST", "/internal/v1/sessions/validate", gatewayService(), body);
    }

    private boolean valid(Session s) throws Exception {
        return validate(s.token(), s.csrf()).body().path("valid").asBoolean();
    }

    private static byte[] sha256(String s) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    }

    // ---- registration ----

    @Test
    void registrationCreatesAnAccountAndNeverStoresOrReturnsThePassword() throws Exception {
        String email = "Mixed.Case-" + UUID.randomUUID().toString().substring(0, 6) + "@Example.TEST";
        Resp r = register(email, PASSWORD);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.raw().body()).doesNotContain(PASSWORD).doesNotContainIgnoringCase("hash").doesNotContain("argon");
        assertThat(r.body().path("email_verified").asBoolean()).isFalse();
        String stored = jdbc.sql("select password_hash from users where email_normalized = :e")
                .param("e", email.toLowerCase()).query(String.class).single();
        assertThat(stored).startsWith("$argon2id$").doesNotContain(PASSWORD);
        assertThat(jdbc.sql("select email from users where email_normalized = :e").param("e", email.toLowerCase())
                .query(String.class).single()).isEqualTo(email);
    }

    @Test
    void registrationRejectsInvalidInputWithoutEchoingSecrets() throws Exception {
        Map<String, String> cases = Map.of(
                "not-an-email", PASSWORD,
                "short-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test", "Short1!",
                "common-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test", "passwordpassword",
                "x'; drop table users;--@example.test", PASSWORD);
        for (Map.Entry<String, String> c : cases.entrySet()) {
            Resp r = register(c.getKey(), c.getValue());
            assertThat(r.status()).as(c.getKey()).isEqualTo(422);
            assertThat(r.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(r.raw().body()).doesNotContain(c.getValue()).doesNotContain("Exception");
        }
        String local = "marathon" + UUID.randomUUID().toString().substring(0, 4);
        Resp containsName = register(local + "@example.test", "xx-" + local + "-yy-zz");
        assertThat(containsName.status()).isEqualTo(422);
        assertThat(call("POST", "/v1/auth/register", anon(), null).status()).isEqualTo(400);
        assertThat(call("POST", "/v1/auth/register", anon(), "{not json").status()).isEqualTo(400);
        assertThat(call("POST", "/v1/auth/register", anon(), Map.of("email", email(), "password", PASSWORD)).status())
                .as("display name is required").isEqualTo(422);
    }

    @Test
    void duplicateEmailsAreRejectedCaseInsensitively() throws Exception {
        String email = email();
        assertThat(register(email, PASSWORD).status()).isEqualTo(201);
        Resp dup = register(email.toUpperCase(), NEW_PASSWORD);
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.code()).isEqualTo("CONFLICT");
        assertThat(jdbc.sql("select count(*) from users where email_normalized = :e").param("e", email)
                .query(Long.class).single()).isEqualTo(1L);
        // The original password is unchanged by the failed duplicate.
        assertThat(login(email, PASSWORD).status()).isEqualTo(200);
    }

    // ---- login ----

    @Test
    void loginSetsAHardenedCookieAndStoresOnlyTheHashOfTheSessionToken() throws Exception {
        String email = email();
        register(email, PASSWORD);
        Resp r = login(email.toUpperCase(), PASSWORD);
        assertThat(r.status()).isEqualTo(200);
        String cookie = r.header("Set-Cookie");
        assertThat(cookie).startsWith(COOKIE + "=").contains("HttpOnly").contains("Secure").contains("SameSite=Lax")
                .contains("Path=/").doesNotContain("Domain");
        String token = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        assertThat(token).hasSizeGreaterThanOrEqualTo(43);
        assertThat(r.raw().body()).doesNotContain(token);
        assertThat(r.header("Cache-Control")).isEqualTo("no-store");
        byte[] stored = jdbc.sql("select token_hash from sessions where token_hash = :h").param("h", sha256(token))
                .query(byte[].class).single();
        assertThat(stored).isEqualTo(sha256(token));
        assertThat(jdbc.sql("select count(*) from sessions where encode(token_hash, 'escape') = :t").param("t", token)
                .query(Long.class).single()).isZero();
        assertThat(r.body().path("csrf_token").asString()).isNotEmpty();
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable() throws Exception {
        String email = email();
        register(email, PASSWORD);
        Resp wrong = login(email, "not-the-password-123");
        Resp unknown = login(email(), "not-the-password-123");
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(wrong.code()).isEqualTo("INVALID_CREDENTIALS").isEqualTo(unknown.code());
        assertThat(wrong.body().path("title").asString()).isEqualTo(unknown.body().path("title").asString());
        assertThat(wrong.body().has("detail")).isFalse();
        assertThat(wrong.header("Set-Cookie")).isNull();
        assertThat(login("", "").status()).isEqualTo(401);
    }

    @Test
    void loggingInAgainRotatesTheSessionAndRevokesThePresentedOne() throws Exception {
        String email = email();
        Session first = registerAndLogin(email);
        Resp again = call("POST", "/v1/auth/login", user(first.userId(), first.sessionId()),
                Map.of("email", email, "password", PASSWORD));
        assertThat(again.status()).isEqualTo(200);
        assertThat(valid(first)).as("old session revoked").isFalse();
        assertThat(jdbc.sql("select revoke_reason from sessions where id = :id").param("id", first.sessionId())
                .query(String.class).single()).isEqualTo("ROTATED");
        String cookie = again.header("Set-Cookie");
        String newToken = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        assertThat(newToken).isNotEqualTo(first.token());
        assertThat(validate(newToken, null).body().path("valid").asBoolean()).isTrue();
    }

    @Test
    void repeatedFailuresLockTheAccountWithoutRevealingWhetherItExists() throws Exception {
        String real = email();
        register(real, PASSWORD);
        String ghost = email();
        for (String target : List.of(real, ghost)) {
            for (int i = 0; i < 5; i++) {
                assertThat(login(target, "wrong-password-" + i).status()).isEqualTo(401);
            }
            Resp locked = login(target, PASSWORD);
            assertThat(locked.status()).as(target).isEqualTo(429);
            assertThat(locked.code()).isEqualTo("RATE_LIMITED");
            assertThat(locked.header("Retry-After")).isNotNull();
        }
        // Even the right password is refused while locked, and works once the lock has passed.
        assertThat(login(real, PASSWORD).status()).isEqualTo(429);
        clock.advance(Duration.ofSeconds(40));
        assertThat(login(real, PASSWORD).status()).isEqualTo(200);
        // Success clears the counter: four more failures do not lock again.
        for (int i = 0; i < 4; i++) {
            assertThat(login(real, "wrong-again-" + i).status()).isEqualTo(401);
        }
        assertThat(login(real, PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void lockoutDelayGrowsWithContinuedFailures() throws Exception {
        String email = email();
        register(email, PASSWORD);
        for (int i = 0; i < 5; i++) {
            login(email, "bad-" + i + "-password");
        }
        int first = Integer.parseInt(login(email, PASSWORD).header("Retry-After"));
        clock.advance(Duration.ofSeconds(40));
        login(email, "still-bad-password");
        int second = Integer.parseInt(login(email, PASSWORD).header("Retry-After"));
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void failedLoginsAgeOutOfTheWindow() throws Exception {
        String email = email();
        register(email, PASSWORD);
        for (int i = 0; i < 4; i++) {
            login(email, "bad-" + i + "-password");
        }
        clock.advance(Duration.ofHours(2));
        login(email, "bad-after-window");
        assertThat(login(email, PASSWORD).status()).as("counter restarted after the window").isEqualTo(200);
    }

    // ---- sessions ----

    @Test
    void validSessionIsAcceptedAndCsrfIsCheckedInConstantTimeAgainstTheStoredToken() throws Exception {
        Session s = registerAndLogin(email());
        Resp ok = validate(s.token(), s.csrf());
        assertThat(ok.body().path("valid").asBoolean()).isTrue();
        assertThat(ok.body().path("csrf_valid").asBoolean()).isTrue();
        assertThat(ok.body().path("user_id").asString()).isEqualTo(s.userId().toString());
        assertThat(validate(s.token(), "wrong").body().path("csrf_valid").asBoolean()).isFalse();
        assertThat(validate(s.token(), null).body().path("csrf_valid").asBoolean()).isFalse();
        assertThat(validate("not-a-session", null).body().path("valid").asBoolean()).isFalse();
        assertThat(validate("x".repeat(500), null).body().path("valid").asBoolean()).isFalse();
    }

    @Test
    void onlyTheGatewayMayValidateSessions() throws Exception {
        Session s = registerAndLogin(email());
        Map<String, String> body = Map.of("session_token", s.token());
        assertThat(call("POST", "/internal/v1/sessions/validate", anon(), body).status()).isEqualTo(403);
        assertThat(call("POST", "/internal/v1/sessions/validate", user(s.userId(), s.sessionId()), body).status()).isEqualTo(403);
        assertThat(call("POST", "/internal/v1/sessions/validate",
                TestTokens.issuer(TestTokens.CALLER, Duration.ofMinutes(2), clock).issueService("identity-access", null), body).status())
                .as("another trusted service is not the gateway").isEqualTo(403);
        assertThat(call("POST", "/internal/v1/sessions/validate", "garbage", body).status()).isEqualTo(401);
    }

    @Test
    void sessionExpiresAfterTheIdleTimeoutButActivityKeepsItAlive() throws Exception {
        Session s = registerAndLogin(email());
        clock.advance(Duration.ofMinutes(20));
        assertThat(valid(s)).isTrue();
        clock.advance(Duration.ofMinutes(20));
        assertThat(valid(s)).as("40 minutes in, but only 20 idle").isTrue();
        clock.advance(Duration.ofMinutes(31));
        assertThat(valid(s)).as("31 idle minutes").isFalse();
        assertThat(jdbc.sql("select revoke_reason from sessions where id = :id").param("id", s.sessionId())
                .query(String.class).single()).isEqualTo("EXPIRED");
    }

    @Test
    void sessionExpiresAtTheAbsoluteLimitEvenWithContinuousActivity() throws Exception {
        Session s = registerAndLogin(email());
        for (int i = 0; i < 24; i++) {
            clock.advance(Duration.ofMinutes(29));
            assertThat(valid(s)).as("step " + i).isTrue();
        }
        clock.advance(Duration.ofMinutes(29));
        assertThat(valid(s)).as("over 12 hours").isFalse();
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() throws Exception {
        Session s = registerAndLogin(email());
        assertThat(call("POST", "/v1/auth/logout", anon(), null).status()).as("no session, no logout").isEqualTo(401);
        Resp out = call("POST", "/v1/auth/logout", user(s.userId(), s.sessionId()), null);
        assertThat(out.status()).isEqualTo(204);
        assertThat(out.header("Set-Cookie")).startsWith(COOKIE + "=;").contains("Max-Age=0");
        assertThat(valid(s)).isFalse();
        assertThat(jdbc.sql("select revoke_reason from sessions where id = :id").param("id", s.sessionId())
                .query(String.class).single()).isEqualTo("LOGOUT");
        assertThat(call("GET", "/v1/me", user(s.userId(), s.sessionId()), null).status())
                .as("a revoked session cannot read the profile even with a still-valid internal token").isEqualTo(401);
    }

    @Test
    void meReturnsOnlyTheVerifiedUserAndIgnoresForgedIdentityHeaders() throws Exception {
        String emailA = email();
        Session a = registerAndLogin(emailA);
        Session b = registerAndLogin(email());
        Resp r = call("GET", "/v1/me", user(a.userId(), a.sessionId()), null,
                "X-User-Id", b.userId().toString(), "X-Tenant-Id", UUID.randomUUID().toString(), "X-User-Role", "ADMIN");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().path("id").asString()).isEqualTo(a.userId().toString());
        assertThat(r.body().path("email").asString()).isEqualTo(emailA);
        assertThat(r.body().path("csrf_token").asString()).isEqualTo(a.csrf());
        assertThat(r.raw().body()).doesNotContain("password");
        assertThat(call("GET", "/v1/me", anon(), null).status()).isEqualTo(401);
        assertThat(call("GET", "/v1/me", user(UUID.randomUUID(), UUID.randomUUID()), null).status())
                .as("token naming a user/session that do not exist").isEqualTo(401);
    }

    // ---- password change ----

    @Test
    void changingThePasswordRevokesEverySessionAndIssuesAFreshOne() throws Exception {
        String email = email();
        Session one = registerAndLogin(email);
        Session two = loginSession(email, PASSWORD);
        Resp r = call("POST", "/v1/auth/password/change", user(one.userId(), one.sessionId()),
                Map.of("current_password", PASSWORD, "new_password", NEW_PASSWORD));
        assertThat(r.status()).isEqualTo(200);
        assertThat(valid(one)).isFalse();
        assertThat(valid(two)).as("other devices are signed out too").isFalse();
        String cookie = r.header("Set-Cookie");
        String fresh = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        assertThat(validate(fresh, null).body().path("valid").asBoolean()).isTrue();
        assertThat(login(email, PASSWORD).status()).isEqualTo(401);
        assertThat(login(email, NEW_PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void passwordChangeNeedsTheCurrentPasswordAndAStrongNewOne() throws Exception {
        String email = email();
        Session s = registerAndLogin(email);
        String token = user(s.userId(), s.sessionId());
        assertThat(call("POST", "/v1/auth/password/change", token, Map.of("current_password", "wrong-wrong-wrong", "new_password", NEW_PASSWORD)).status())
                .isEqualTo(401);
        assertThat(call("POST", "/v1/auth/password/change", token, Map.of("current_password", PASSWORD, "new_password", "short")).status())
                .isEqualTo(422);
        assertThat(call("POST", "/v1/auth/password/change", anon(), Map.of("current_password", PASSWORD, "new_password", NEW_PASSWORD)).status())
                .isEqualTo(401);
        assertThat(valid(s)).as("failed attempts do not end the session").isTrue();
        for (int i = 0; i < 4; i++) {
            call("POST", "/v1/auth/password/change", token, Map.of("current_password", "wrong-wrong-" + i, "new_password", NEW_PASSWORD));
        }
        assertThat(call("POST", "/v1/auth/password/change", token, Map.of("current_password", PASSWORD, "new_password", NEW_PASSWORD)).status())
                .as("guessing the current password is throttled like login").isEqualTo(429);
    }

    // ---- password reset ----

    private String requestReset(String email) throws Exception {
        Resp r = call("POST", "/v1/auth/password-reset/request", anon(), Map.of("email", email));
        assertThat(r.status()).isEqualTo(202);
        return notifier.lastTokenFor(email);
    }

    @Test
    void resetRequestLooksIdenticalForKnownAndUnknownAccountsAndOnlySendsForRealOnes() throws Exception {
        String email = email();
        register(email, PASSWORD);
        int before = notifier.count();
        Resp known = call("POST", "/v1/auth/password-reset/request", anon(), Map.of("email", email));
        Resp unknown = call("POST", "/v1/auth/password-reset/request", anon(), Map.of("email", email()));
        assertThat(known.status()).isEqualTo(202).isEqualTo(unknown.status());
        assertThat(known.raw().body()).isEqualTo(unknown.raw().body());
        assertThat(notifier.count()).isEqualTo(before + 1);
        assertThat(call("POST", "/v1/auth/password-reset/request", anon(), Map.of("email", "nope")).status()).isEqualTo(422);
    }

    @Test
    void resetTokenWorksOnceChangesThePasswordAndSignsEverythingOut() throws Exception {
        String email = email();
        Session s = registerAndLogin(email);
        String token = requestReset(email);
        assertThat(token).isNotNull();
        byte[] stored = jdbc.sql("select token_hash from password_reset_tokens where token_hash = :h").param("h", sha256(token))
                .query(byte[].class).single();
        assertThat(stored).as("only the hash is stored").isEqualTo(sha256(token));

        Resp ok = call("POST", "/v1/auth/password-reset/confirm", anon(), Map.of("token", token, "new_password", NEW_PASSWORD));
        assertThat(ok.status()).isEqualTo(204);
        assertThat(valid(s)).isFalse();
        assertThat(login(email, PASSWORD).status()).isEqualTo(401);
        assertThat(login(email, NEW_PASSWORD).status()).isEqualTo(200);

        Resp reuse = call("POST", "/v1/auth/password-reset/confirm", anon(), Map.of("token", token, "new_password", "another-Strong-pass-77"));
        assertThat(reuse.status()).isEqualTo(400);
        assertThat(login(email, "another-Strong-pass-77").status()).isEqualTo(401);
    }

    @Test
    void resetTokenExpires() throws Exception {
        String email = email();
        register(email, PASSWORD);
        String token = requestReset(email);
        clock.advance(Duration.ofMinutes(31));
        Resp r = call("POST", "/v1/auth/password-reset/confirm", anon(), Map.of("token", token, "new_password", NEW_PASSWORD));
        assertThat(r.status()).isEqualTo(400);
        assertThat(login(email, PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void invalidResetTokensAreRefusedIdentically() throws Exception {
        for (String bad : new String[] {"garbage", "", "a".repeat(300), UUID.randomUUID().toString()}) {
            Resp r = call("POST", "/v1/auth/password-reset/confirm", anon(), Map.of("token", bad, "new_password", NEW_PASSWORD));
            assertThat(r.status()).as(bad).isEqualTo(400);
            assertThat(r.body().path("detail").asString()).isEqualTo("Invalid or expired reset token");
        }
    }

    @Test
    void weakNewPasswordDoesNotConsumeTheResetToken() throws Exception {
        String email = email();
        register(email, PASSWORD);
        String token = requestReset(email);
        assertThat(call("POST", "/v1/auth/password-reset/confirm", anon(), Map.of("token", token, "new_password", "weak")).status())
                .isEqualTo(422);
        assertThat(call("POST", "/v1/auth/password-reset/confirm", anon(), Map.of("token", token, "new_password", NEW_PASSWORD)).status())
                .isEqualTo(204);
    }

    @Test
    void aNewResetRequestReplacesThePreviousTokenAndRapidRepeatsAreSuppressed() throws Exception {
        String email = email();
        register(email, PASSWORD);
        String first = requestReset(email);
        int count = notifier.count();
        requestReset(email);
        assertThat(notifier.count()).as("second request within a minute sends nothing").isEqualTo(count);
        clock.advance(Duration.ofSeconds(61));
        String second = requestReset(email);
        assertThat(second).isNotEqualTo(first);
        assertThat(call("POST", "/v1/auth/password-reset/confirm", anon(), Map.of("token", first, "new_password", NEW_PASSWORD)).status())
                .as("older token is dead").isEqualTo(400);
        assertThat(call("POST", "/v1/auth/password-reset/confirm", anon(), Map.of("token", second, "new_password", NEW_PASSWORD)).status())
                .isEqualTo(204);
    }

    @Test
    void concurrentUseOfOneResetTokenSucceedsExactlyOnce() throws Exception {
        String email = email();
        register(email, PASSWORD);
        String token = requestReset(email);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String pw = "Concurrent-pass-" + i + "-zq!";
                results.add(pool.submit(() -> call("POST", "/v1/auth/password-reset/confirm", anon(),
                        Map.of("token", token, "new_password", pw)).status()));
            }
            int ok = 0;
            for (Future<Integer> f : results) {
                if (f.get() == 204) {
                    ok++;
                } else {
                    assertThat(f.get()).isEqualTo(400);
                }
            }
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- safe errors, secrets, persistence ----

    @Test
    void secretsNeverAppearInLogs(CapturedOutput output) throws Exception {
        String email = email();
        Session s = registerAndLogin(email);
        String reset = requestReset(email);
        call("POST", "/v1/auth/password-reset/confirm", anon(), Map.of("token", reset, "new_password", NEW_PASSWORD));
        login(email, "a-wrong-password-for-logs");
        call("POST", "/v1/auth/password/change", user(s.userId(), s.sessionId()), Map.of("current_password", "bad-pass-for-logs", "new_password", "x"));
        AtomicReference<String> logs = new AtomicReference<>(output.getAll());
        for (String secret : List.of(PASSWORD, NEW_PASSWORD, "a-wrong-password-for-logs", "bad-pass-for-logs", s.token(), s.csrf(), reset)) {
            assertThat(logs.get()).as("log must not contain a secret").doesNotContain(secret);
        }
    }

    @Test
    void errorResponsesAreProblemDocumentsWithoutInternalDetail() throws Exception {
        Resp r = call("POST", "/v1/auth/login", anon(), "{\"email\": [1,2");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw().headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(r.raw().body()).doesNotContain("Exception").doesNotContain("jackson").doesNotContain("at com.");
        assertThat(r.body().has("request_id")).isTrue();
    }

    @Test
    void databaseConstraintsProtectTheIdentityData() {
        UUID id = UUID.randomUUID();
        String email = email();
        jdbc.sql("insert into users (id, email, email_normalized, display_name, password_hash, password_changed_at, created_at, updated_at) values (:id, :e, :e, 'n', 'h', now(), now(), now())")
                .param("id", id).param("e", email).update();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("insert into users (id, email, email_normalized, display_name, password_hash, password_changed_at, created_at, updated_at) values (:id, :e, :e, 'n', 'h', now(), now(), now())")
                .param("id", UUID.randomUUID()).param("e", email).update()).hasMessageContaining("users_email_normalized_key");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("insert into users (id, email, email_normalized, display_name, password_hash, password_changed_at, created_at, updated_at) values (:id, 'A@B.C', 'A@B.C', 'n', 'h', now(), now(), now())")
                .param("id", UUID.randomUUID()).update()).hasMessageContaining("users_email_lower_chk");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("insert into sessions (id, user_id, token_hash, csrf_token, created_at, last_seen_at, absolute_expires_at) values (:id, :uid, :h, 'c', now(), now(), now())")
                .param("id", UUID.randomUUID()).param("uid", id).param("h", new byte[5]).update()).hasMessageContaining("sessions_token_hash_len_chk");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("insert into sessions (id, user_id, token_hash, csrf_token, created_at, last_seen_at, absolute_expires_at) values (:id, :uid, :h, 'c', now(), now(), now())")
                .param("id", UUID.randomUUID()).param("uid", UUID.randomUUID()).param("h", new byte[32]).update()).hasMessageContaining("foreign key");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("insert into sessions (id, user_id, token_hash, csrf_token, created_at, last_seen_at, absolute_expires_at, revoked_at) values (:id, :uid, :h, 'c', now(), now(), now(), now())")
                .param("id", UUID.randomUUID()).param("uid", id).param("h", new byte[32]).update()).hasMessageContaining("sessions_revoked_chk");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("update users set status = 'WEIRD' where id = :id").param("id", id).update())
                .hasMessageContaining("users_status_chk");
    }

    @Test
    void disabledAccountsCannotLogInAndTheirSessionsStopWorking() throws Exception {
        String email = email();
        Session s = registerAndLogin(email);
        jdbc.sql("update users set status = 'DISABLED' where id = :id").param("id", s.userId()).update();
        assertThat(valid(s)).isFalse();
        assertThat(login(email, PASSWORD).status()).isEqualTo(401);
    }
}
