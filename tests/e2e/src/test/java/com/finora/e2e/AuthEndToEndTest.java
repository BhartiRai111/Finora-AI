package com.finora.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.testkit.TestTokens;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Real processes, real HTTP, real PostgreSQL, real sessions: only the gateway's public port is used and nothing in this
 * test fakes authentication. Requires FINORA_DEV_DB_PASSWORD / FINORA_DB_HOST / FINORA_DB_PORT in the environment.
 */
class AuthEndToEndTest extends E2eBase {

    private static final String NEW_PASSWORD = "e2e-Another-Staple-77-zebra";

    @Test
    void registerLoginReadProfileThroughTheGateway() throws Exception {
        String email = email();
        assertThat(post("/v1/auth/register", null, null, Map.of("email", email, "password", PASSWORD, "display_name", "E2E User")).status())
                .isEqualTo(201);
        Login l = login(email, PASSWORD);

        assertThat(l.setCookie()).startsWith(COOKIE + "=").contains("HttpOnly").contains("Secure").contains("SameSite=Lax").contains("Path=/");
        Resp me = get("/v1/me", l.token(), null);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().path("email").asString()).isEqualTo(email);
        assertThat(me.raw().body()).doesNotContain(l.token()).doesNotContain(PASSWORD);

        assertThat(get("/v1/me", null, null).status()).as("no cookie").isEqualTo(401);
        assertThat(get("/v1/me", "garbage-token", null).status()).as("invalid cookie").isEqualTo(401);
        assertThat(me.raw().headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    void forgedIdentityHeadersAreIgnoredAndCannotAuthenticate() throws Exception {
        String emailA = email();
        Login a = registerAndLogin(emailA);
        Login b = registerAndLogin(email());
        Map<String, String> forged = Map.of("X-User-Id", UUID.randomUUID().toString(), "X-Tenant-Id", UUID.randomUUID().toString(),
                "X-User-Role", "OWNER", "X-Forwarded-For", "10.9.8.7");
        assertThat(get("/v1/me", null, forged).status()).isEqualTo(401);
        Resp asA = get("/v1/me", a.token(), forged);
        assertThat(asA.status()).isEqualTo(200);
        assertThat(asA.body().path("email").asString()).isEqualTo(emailA);
        assertThat(asA.body().path("id").asString()).isNotEqualTo(b.userId());
        assertThat(get("/v1/me", null, Map.of("Authorization", "Bearer " + TestTokens.gatewayUser("identity-access", null))).status())
                .as("a hand-made internal token is not a session").isEqualTo(401);
    }

    @Test
    void internalEndpointsAreNotReachableThroughTheGateway() throws Exception {
        Login l = registerAndLogin(email());
        assertThat(post("/internal/v1/sessions/validate", null, null, Map.of("session_token", l.token())).status()).isEqualTo(404);
        assertThat(get("/internal/v1/ping", l.token(), null).status()).isEqualTo(404);
    }

    @Test
    void logoutNeedsCsrfAndAllowedOriginThenRevokesTheSession() throws Exception {
        Login l = registerAndLogin(email());
        assertThat(post("/v1/auth/logout", l.token(), null, null).status()).as("no CSRF token").isEqualTo(403);
        assertThat(post("/v1/auth/logout", l.token(), Map.of("X-CSRF-Token", "wrong"), null).status()).isEqualTo(403);
        assertThat(post("/v1/auth/logout", l.token(), Map.of("X-CSRF-Token", l.csrf(), "Origin", "https://evil.example"), null).status())
                .as("cross-site origin").isEqualTo(403);
        assertThat(get("/v1/me", l.token(), null).status()).as("still logged in after refused attempts").isEqualTo(200);

        Resp out = post("/v1/auth/logout", l.token(), Map.of("X-CSRF-Token", l.csrf(), "Origin", "https://app.example.test"), null);
        assertThat(out.status()).isEqualTo(204);
        assertThat(out.raw().headers().firstValue("Set-Cookie").orElse("")).contains("Max-Age=0");
        assertThat(get("/v1/me", l.token(), null).status()).as("revoked session").isEqualTo(401);
    }

    @Test
    void loggingInAgainRotatesTheSession() throws Exception {
        String email = email();
        Login first = registerAndLogin(email);
        Resp second = post("/v1/auth/login", first.token(), null, Map.of("email", email, "password", PASSWORD));
        assertThat(second.status()).isEqualTo(200);
        String newToken = cookieValue(second.raw().headers().firstValue("Set-Cookie").orElseThrow());
        SECRETS.add(newToken);
        assertThat(newToken).isNotEqualTo(first.token());
        assertThat(get("/v1/me", first.token(), null).status()).as("old session revoked").isEqualTo(401);
        assertThat(get("/v1/me", newToken, null).status()).isEqualTo(200);
    }

    @Test
    void changingThePasswordSignsOutEveryDevice() throws Exception {
        String email = email();
        Login one = registerAndLogin(email);
        Login two = login(email, PASSWORD);
        Resp r = post("/v1/auth/password/change", one.token(), Map.of("X-CSRF-Token", one.csrf()),
                Map.of("current_password", PASSWORD, "new_password", NEW_PASSWORD));
        assertThat(r.status()).isEqualTo(200);
        String fresh = cookieValue(r.raw().headers().firstValue("Set-Cookie").orElseThrow());
        SECRETS.add(fresh);
        assertThat(get("/v1/me", one.token(), null).status()).isEqualTo(401);
        assertThat(get("/v1/me", two.token(), null).status()).isEqualTo(401);
        assertThat(get("/v1/me", fresh, null).status()).isEqualTo(200);
        assertThat(post("/v1/auth/login", null, null, Map.of("email", email, "password", PASSWORD)).status()).isEqualTo(401);
        assertThat(post("/v1/auth/login", null, null, Map.of("email", email, "password", NEW_PASSWORD)).status()).isEqualTo(200);
    }

    @Test
    void sessionExpiresAfterTheIdleTimeout() throws Exception {
        Login l = registerAndLogin(email());
        assertThat(get("/v1/me", l.token(), null).status()).isEqualTo(200);
        Thread.sleep(7500);
        assertThat(get("/v1/me", l.token(), null).status()).as("idle timeout is 6s in this run").isEqualTo(401);
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
        assertThat(locked.raw().headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void registrationErrorsAreSafe() throws Exception {
        String email = email();
        assertThat(post("/v1/auth/register", null, null, Map.of("email", email, "password", "short", "display_name", "X")).status()).isEqualTo(422);
        assertThat(post("/v1/auth/register", null, null, Map.of("email", email, "password", PASSWORD, "display_name", "X")).status()).isEqualTo(201);
        Resp dup = post("/v1/auth/register", null, null, Map.of("email", email, "password", PASSWORD, "display_name", "X"));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.raw().body()).doesNotContain("Exception").doesNotContain("users_email").doesNotContain("postgres");
    }

    @Test
    void passwordResetAcceptsTheRequestButHonestlyCannotDeliverWithoutAMailProvider() throws Exception {
        String email = email();
        registerAndLogin(email);
        Resp r = post("/v1/auth/password-reset/request", null, null, Map.of("email", email));
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.raw().body()).doesNotContain("token");
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

}
