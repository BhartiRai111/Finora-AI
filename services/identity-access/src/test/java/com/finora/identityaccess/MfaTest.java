package com.finora.identityaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import tools.jackson.databind.JsonNode;

/** TOTP multi-factor authentication, recovery codes, step-up and the mandatory-MFA rule for Owners and Admins. */
class MfaTest extends IdentityTestSupport {

    private Resp validate(TU u) throws Exception {
        return call("POST", "/internal/v1/sessions/validate", gatewayService(), Map.of("session_token", u.cookie));
    }

    @Test
    void enrolmentReturnsASecretOnceAndRecoveryCodesOnceAndStoresThemProtected() throws Exception {
        TU u = newUser();
        Resp enroll = as(u, "POST", "/v1/auth/mfa/enroll", null);
        assertThat(enroll.status()).isEqualTo(200);
        String secret = enroll.body().path("secret").asString();
        assertThat(enroll.body().path("otpauth_uri").asString()).startsWith("otpauth://totp/").contains("secret=" + secret).contains("issuer=Finora%20AI");
        u.mfaSecret = secret;
        Resp wrong = as(u, "POST", "/v1/auth/mfa/confirm", Map.of("code", "000000"));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(as(u, "GET", "/v1/me", null).body().path("mfa_enabled").asBoolean()).as("not enabled until confirmed").isFalse();

        Resp confirm = as(u, "POST", "/v1/auth/mfa/confirm", Map.of("code", freshCode(u)));
        assertThat(confirm.status()).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        confirm.body().path("recovery_codes").forEach(c -> codes.add(c.asString()));
        assertThat(codes).hasSize(10).doesNotHaveDuplicates().allMatch(c -> c.matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}"));

        byte[] cipher = jdbc.sql("select secret_ciphertext from mfa_factors where user_id = :u").param("u", u.userId).query(byte[].class).single();
        assertThat(new String(cipher, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(secret);
        assertThat(cipher.length).isGreaterThan(12 + 20);
        for (String c : codes) {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(c.replace("-", "").getBytes());
            assertThat(jdbc.sql("select count(*) from mfa_recovery_codes where user_id = :u and code_hash = :h").param("u", u.userId)
                    .param("h", hash).query(Long.class).single()).isEqualTo(1L);
        }
        adopt(u, confirm);
        assertThat(as(u, "POST", "/v1/auth/mfa/enroll", null).status()).as("cannot re-enrol").isEqualTo(409);
    }

    @Test
    void enablingMfaSignsOutEveryOtherSession() throws Exception {
        TU u = newUser();
        TU other = new TU(u.email);
        other.userId = u.userId;
        login(other);
        withMfa(u);
        assertThat(validate(other).body().path("valid").asBoolean()).isFalse();
        assertThat(validate(u).body().path("valid").asBoolean()).isTrue();
    }

    @Test
    void aSessionThatHasNotCompletedMfaCanDoNothingElse() throws Exception {
        TU u = withMfa(newUser());
        UUID space = createWorkspace(u, "Pending Ltd");
        TU again = new TU(u.email);
        again.userId = u.userId;
        again.mfaSecret = u.mfaSecret;
        Resp login = call("POST", "/v1/auth/login", anon(), Map.of("email", u.email, "password", PASSWORD));
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body().path("mfa_required").asBoolean()).isTrue();
        adopt(again, login);

        Resp v = validate(again);
        assertThat(v.body().path("mfa_pending").asBoolean()).isTrue();
        assertThat(v.body().path("memberships")).as("no workspace access while pending").isEmpty();
        for (Resp r : List.of(as(again, "GET", "/v1/me", null), as(again, "GET", "/v1/workspaces", null),
                inSpace(again, space, "GET", "/members", null), as(again, "POST", "/v1/workspaces", Map.of("name", "X")),
                as(again, "POST", "/v1/auth/step-up", Map.of("password", PASSWORD)))) {
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.code()).isEqualTo("MFA_REQUIRED");
        }

        Resp wrong = as(again, "POST", "/v1/auth/mfa/verify", Map.of("code", "123456"));
        assertThat(wrong.status()).isEqualTo(401);
        Resp ok = as(again, "POST", "/v1/auth/mfa/verify", Map.of("code", freshCode(again)));
        assertThat(ok.status()).as(ok.raw().body()).isEqualTo(200);
        assertThat(ok.body().path("mfa_required").asBoolean()).isFalse();
        assertThat(validate(again).body().path("valid").asBoolean()).as("pending session was replaced").isFalse();
        adopt(again, ok);
        assertThat(as(again, "GET", "/v1/me", null).status()).isEqualTo(200);
        assertThat(inSpace(again, space, "GET", "/members", null).status()).isEqualTo(200);
    }

    @Test
    void aTimeStepCannotBeReplayed() throws Exception {
        TU u = withMfa(newUser());
        Resp login = call("POST", "/v1/auth/login", anon(), Map.of("email", u.email, "password", PASSWORD));
        TU p = new TU(u.email);
        p.userId = u.userId;
        p.mfaSecret = u.mfaSecret;
        adopt(p, login);
        String code = freshCode(u);
        assertThat(as(p, "POST", "/v1/auth/mfa/verify", Map.of("code", code)).status()).isEqualTo(200);
        Resp login2 = call("POST", "/v1/auth/login", anon(), Map.of("email", u.email, "password", PASSWORD));
        TU p2 = new TU(u.email);
        p2.userId = u.userId;
        adopt(p2, login2);
        assertThat(as(p2, "POST", "/v1/auth/mfa/verify", Map.of("code", code)).status()).as("same code, same step").isEqualTo(401);
    }

    @Test
    void recoveryCodesWorkOnceAndRepeatedWrongCodesLockTheAccountFactor() throws Exception {
        TU u = newUser();
        Resp enroll = as(u, "POST", "/v1/auth/mfa/enroll", null);
        u.mfaSecret = enroll.body().path("secret").asString();
        Resp confirm = as(u, "POST", "/v1/auth/mfa/confirm", Map.of("code", freshCode(u)));
        adopt(u, confirm);
        String recovery = confirm.body().path("recovery_codes").get(0).asString();

        TU p = new TU(u.email);
        p.userId = u.userId;
        adopt(p, call("POST", "/v1/auth/login", anon(), Map.of("email", u.email, "password", PASSWORD)));
        assertThat(as(p, "POST", "/v1/auth/mfa/verify", Map.of("recovery_code", recovery.toLowerCase())).status()).isEqualTo(200);

        TU p2 = new TU(u.email);
        p2.userId = u.userId;
        adopt(p2, call("POST", "/v1/auth/login", anon(), Map.of("email", u.email, "password", PASSWORD)));
        assertThat(as(p2, "POST", "/v1/auth/mfa/verify", Map.of("recovery_code", recovery)).status()).as("used").isEqualTo(401);
        for (int i = 0; i < 4; i++) {
            as(p2, "POST", "/v1/auth/mfa/verify", Map.of("code", "00000" + i));
        }
        Resp locked = as(p2, "POST", "/v1/auth/mfa/verify", Map.of("code", "000009"));
        assertThat(locked.status()).isEqualTo(429);
        assertThat(locked.header("Retry-After")).isNotNull();
    }

    @Test
    void stepUpNeedsThePasswordAndTheSecondFactor() throws Exception {
        TU u = withMfa(newUser());
        assertThat(as(u, "POST", "/v1/auth/step-up", Map.of("password", PASSWORD)).status()).as("password alone").isEqualTo(401);
        assertThat(as(u, "POST", "/v1/auth/step-up", Map.of("password", "wrong-password-123", "code", freshCode(u))).status()).isEqualTo(401);
        Resp ok = as(u, "POST", "/v1/auth/step-up", Map.of("password", PASSWORD, "code", freshCode(u)));
        assertThat(ok.status()).isEqualTo(200);
        TU plain = newUser();
        assertThat(as(plain, "POST", "/v1/auth/step-up", Map.of("password", PASSWORD)).status()).as("no MFA: password is enough").isEqualTo(200);
        assertThat(call("POST", "/v1/auth/step-up", anon(), Map.of("password", PASSWORD)).status()).isEqualTo(401);
    }

    @Test
    void mfaIsMandatoryWhileHoldingAnOwnerOrAdminRoleButOptionalOtherwise() throws Exception {
        TU owner = withMfa(newUser());
        createWorkspace(owner, "Locked In Ltd");
        Resp refused = as(owner, "POST", "/v1/auth/mfa/disable", Map.of("password", PASSWORD, "code", freshCode(owner)));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().path("detail").asString()).contains("mandatory");

        TU plain = withMfa(newUser());
        Resp ok = as(plain, "POST", "/v1/auth/mfa/disable", Map.of("password", PASSWORD, "code", freshCode(plain)));
        assertThat(ok.status()).as(ok.raw().body()).isEqualTo(200);
        adopt(plain, ok);
        assertThat(as(plain, "GET", "/v1/me", null).body().path("mfa_enabled").asBoolean()).isFalse();
        assertThat(jdbc.sql("select count(*) from mfa_factors where user_id = :u").param("u", plain.userId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from mfa_recovery_codes where user_id = :u").param("u", plain.userId).query(Long.class).single()).isZero();
    }

    @Test
    void disablingNeedsPasswordAndASecondFactor() throws Exception {
        TU u = withMfa(newUser());
        assertThat(as(u, "POST", "/v1/auth/mfa/disable", Map.of("password", PASSWORD)).status()).isEqualTo(401);
        assertThat(as(u, "POST", "/v1/auth/mfa/disable", Map.of("password", "nope-nope-nope-1", "code", freshCode(u))).status()).isEqualTo(401);
        assertThat(as(u, "GET", "/v1/me", null).body().path("mfa_enabled").asBoolean()).isTrue();
    }

    @Test
    void mfaMaterialNeverReachesTheLogs(CapturedOutput output) throws Exception {
        TU u = newUser();
        Resp enroll = as(u, "POST", "/v1/auth/mfa/enroll", null);
        u.mfaSecret = enroll.body().path("secret").asString();
        Resp confirm = as(u, "POST", "/v1/auth/mfa/confirm", Map.of("code", freshCode(u)));
        JsonNode codes = confirm.body().path("recovery_codes");
        as(u, "POST", "/v1/auth/mfa/verify", Map.of("code", "999999"));
        assertThat(output.getAll()).doesNotContain(u.mfaSecret).doesNotContain(codes.get(0).asString());
    }

    @Test
    void stepUpExpiresAfterTenMinutes() throws Exception {
        TU owner = withMfa(newUser());
        UUID space = createWorkspace(owner, "Timing Ltd");
        TU member = newUser();
        addMember(owner, space, member, "VIEWER");
        UUID mid = membershipId(member, space);
        stepUp(owner);
        assertThat(inSpace(owner, space, "PATCH", "/members/" + mid, Map.of("role", "AUDITOR")).status()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(9));
        assertThat(inSpace(owner, space, "PATCH", "/members/" + mid, Map.of("role", "VIEWER")).status()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(2));
        assertThat(inSpace(owner, space, "PATCH", "/members/" + mid, Map.of("role", "AUDITOR")).code()).isEqualTo("STEP_UP_REQUIRED");
    }
}
