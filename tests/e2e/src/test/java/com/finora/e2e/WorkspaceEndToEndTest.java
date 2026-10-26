package com.finora.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Workspaces, invitations, mandatory MFA and revocation through the real gateway and identity-access processes.
 * Invitation links are read from the development outbox file (no email provider exists; nothing is emailed).
 */
class WorkspaceEndToEndTest extends E2eBase {

    private static Map<String, String> csrf(Login l) {
        return Map.of("X-CSRF-Token", l.csrf());
    }

    private static Login adopt(Resp r, String email) {
        String setCookie = r.raw().headers().firstValue("Set-Cookie").orElseThrow();
        String token = cookieValue(setCookie);
        String csrf = r.body().path("csrf_token").asString();
        SECRETS.add(token);
        SECRETS.add(csrf);
        return new Login(token, csrf, r.body().path("user").path("id").asString(), setCookie);
    }

    static String totp(String base32, Instant now) throws GeneralSecurityException {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : base32.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(out.toByteArray(), "HmacSHA1"));
        byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(now.getEpochSecond() / 30).array());
        int o = h[h.length - 1] & 0xf;
        int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
        return String.format("%06d", bin % 1_000_000);
    }

    private static String inviteLinkFor(String email) throws Exception {
        String found = null;
        for (String line : Files.readAllLines(devOutbox)) {
            JsonNode n = JSON.readTree(line);
            if (n.path("to").asString().equalsIgnoreCase(email)) {
                found = n.path("link").asString();
            }
        }
        assertThat(found).as("invitation captured in the development outbox").isNotNull();
        return found.substring(found.indexOf("#token=") + 7);
    }

    @Test
    void aWorkspaceCanBeCreatedSharedAndRevokedThroughTheGateway() throws Exception {
        String ownerEmail = email();
        Login owner = registerAndLogin(ownerEmail);

        // Create a workspace (state change: CSRF required).
        assertThat(post("/v1/workspaces", owner.token(), null, Map.of("name", "E2E Traders")).status()).as("no CSRF token").isEqualTo(403);
        Resp created = post("/v1/workspaces", owner.token(), csrf(owner), Map.of("name", "E2E Traders"));
        assertThat(created.status()).isEqualTo(201);
        String space = created.body().path("id").asString();
        assertThat(get("/v1/workspaces", owner.token(), null).body().path("items")).hasSize(2);

        // Owners must use MFA before administering a workspace (DEC-20).
        Resp noMfa = get("/v1/spaces/" + space + "/members", owner.token(), null);
        assertThat(noMfa.status()).isEqualTo(403);
        assertThat(noMfa.body().path("code").asString()).isEqualTo("MFA_REQUIRED");

        Resp enroll = post("/v1/auth/mfa/enroll", owner.token(), csrf(owner), null);
        assertThat(enroll.status()).isEqualTo(200);
        String secret = enroll.body().path("secret").asString();
        SECRETS.add(secret);
        Resp confirm = post("/v1/auth/mfa/confirm", owner.token(), csrf(owner), Map.of("code", totp(secret, Instant.now())));
        assertThat(confirm.status()).as(confirm.raw().body()).isEqualTo(200);
        List<String> recovery = new ArrayList<>();
        confirm.body().path("recovery_codes").forEach(c -> recovery.add(c.asString()));
        SECRETS.addAll(recovery);
        assertThat(get("/v1/me", owner.token(), null).status()).as("old session was replaced").isEqualTo(401);
        Login ownerMfa = adopt(confirm, ownerEmail);
        Resp members = get("/v1/spaces/" + space + "/members", ownerMfa.token(), null);
        assertThat(members.status()).isEqualTo(200);
        assertThat(members.body().path("items")).hasSize(1);

        // Another user cannot see the workspace, with or without forged headers.
        String memberEmail = email();
        Login member = registerAndLogin(memberEmail);
        assertThat(get("/v1/spaces/" + space, member.token(), null).status()).isEqualTo(404);
        assertThat(get("/v1/spaces/" + space + "/members", member.token(),
                Map.of("X-Tenant-Id", space, "X-User-Id", owner.userId(), "X-User-Role", "OWNER")).status()).isEqualTo(404);

        // Invite: needs CSRF; delivery is honestly reported as development-captured, not sent.
        Map<String, Object> invite = Map.of("email", memberEmail, "role", "VIEWER");
        assertThat(post("/v1/spaces/" + space + "/invitations", ownerMfa.token(), null, invite).status()).isEqualTo(403);
        Resp invited = post("/v1/spaces/" + space + "/invitations", ownerMfa.token(), csrf(ownerMfa), invite);
        assertThat(invited.status()).isEqualTo(201);
        assertThat(invited.body().path("delivery").asString()).isEqualTo("DEV_CAPTURED");
        String inviteToken = inviteLinkFor(memberEmail);
        SECRETS.add(inviteToken);
        assertThat(invited.raw().body()).doesNotContain(inviteToken);

        // Accept as the invitee.
        Map<String, Object> accept = Map.of("token", inviteToken);
        assertThat(post("/v1/invitations/accept", member.token(), null, accept).status()).as("CSRF").isEqualTo(403);
        Resp accepted = post("/v1/invitations/accept", member.token(), csrf(member), accept);
        assertThat(accepted.status()).as(accepted.raw().body()).isEqualTo(200);
        assertThat(post("/v1/invitations/accept", member.token(), csrf(member), accept).status()).as("reuse").isEqualTo(400);
        assertThat(get("/v1/spaces/" + space, member.token(), null).status()).isEqualTo(200);
        assertThat(get("/v1/spaces/" + space + "/members", member.token(), null).status()).as("viewer lacks the permission").isEqualTo(403);
        assertThat(get("/v1/workspaces", member.token(), null).body().path("items")).hasSize(2);

        // Revoke the member: the very next request with the same cookie loses access.
        String membershipId = null;
        for (JsonNode m : get("/v1/spaces/" + space + "/members", ownerMfa.token(), null).body().path("items")) {
            if (m.path("email").asString().equalsIgnoreCase(memberEmail)) {
                membershipId = m.path("membership_id").asString();
            }
        }
        assertThat(membershipId).isNotNull();
        assertThat(delete("/v1/spaces/" + space + "/members/" + membershipId, ownerMfa.token(), null).status()).as("CSRF").isEqualTo(403);
        assertThat(delete("/v1/spaces/" + space + "/members/" + membershipId, ownerMfa.token(), csrf(ownerMfa)).status()).isEqualTo(204);
        assertThat(get("/v1/spaces/" + space, member.token(), null).status()).isEqualTo(404);
        assertThat(get("/v1/me", member.token(), null).status()).as("their account still works").isEqualTo(200);
        assertThat(get("/v1/workspaces", member.token(), null).body().path("items")).hasSize(1);

        // Sign in again: MFA is required before anything else.
        Resp relogin = post("/v1/auth/login", null, null, Map.of("email", ownerEmail, "password", PASSWORD));
        assertThat(relogin.body().path("mfa_required").asBoolean()).isTrue();
        Login pending = adopt(relogin, ownerEmail);
        for (String path : new String[] {"/v1/me", "/v1/workspaces", "/v1/spaces/" + space + "/members"}) {
            Resp r = get(path, pending.token(), null);
            assertThat(r.status()).as(path).isEqualTo(403);
            assertThat(r.body().path("code").asString()).isEqualTo("MFA_REQUIRED");
        }
        Resp verified = post("/v1/auth/mfa/verify", pending.token(), csrf(pending), Map.of("recovery_code", recovery.get(0)));
        assertThat(verified.status()).as(verified.raw().body()).isEqualTo(200);
        assertThat(get("/v1/me", pending.token(), null).status()).as("pending session replaced").isEqualTo(401);
        Login full = adopt(verified, ownerEmail);
        assertThat(get("/v1/spaces/" + space + "/members", full.token(), null).status()).isEqualTo(200);
        assertThat(post("/v1/auth/mfa/verify", full.token(), csrf(full), Map.of("recovery_code", recovery.get(0))).status())
                .as("a verified session has nothing to verify").isEqualTo(409);
    }

    @Test
    void secretsNeverReachTheLogsAndInvitationsAreReportedHonestly() throws Exception {
        Thread.sleep(300);
        String all = Files.readString(logDir.resolve("identity-access.log")) + Files.readString(logDir.resolve("api-gateway.log"));
        assertThat(all).contains("DEVELOPMENT invitation outbox enabled");
        for (String secret : new ArrayList<>(SECRETS)) {
            assertThat(all).as("a secret appeared in a log").doesNotContain(secret);
        }
    }
}
