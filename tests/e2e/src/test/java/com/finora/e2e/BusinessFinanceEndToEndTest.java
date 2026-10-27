package com.finora.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Business finance through the real stack: session cookie + CSRF -> gateway -> real identity-access (sessions, MFA,
 * memberships, invitations) -> real finance service -> PostgreSQL. No authentication or authorization is faked.
 */
class BusinessFinanceEndToEndTest extends E2eBase {

    private static Map<String, String> csrf(Login l) {
        return Map.of("X-CSRF-Token", l.csrf());
    }

    private static Map<String, String> write(Login l, String key) {
        return Map.of("X-CSRF-Token", l.csrf(), "Idempotency-Key", key);
    }

    private static Login adopt(Resp r) {
        String setCookie = r.raw().headers().firstValue("Set-Cookie").orElseThrow();
        String token = cookieValue(setCookie);
        String csrf = r.body().path("csrf_token").asString();
        SECRETS.add(token);
        SECRETS.add(csrf);
        return new Login(token, csrf, r.body().path("user").path("id").asString(), setCookie);
    }

    /** Registers a user, enrols MFA (mandatory for Owners and Admins) and returns the MFA session. */
    private static Login mfaUser() throws Exception {
        Login plain = registerAndLogin(email());
        Resp enroll = post("/v1/auth/mfa/enroll", plain.token(), csrf(plain), null);
        String secret = enroll.body().path("secret").asString();
        SECRETS.add(secret);
        Resp confirm = post("/v1/auth/mfa/confirm", plain.token(), csrf(plain), Map.of("code", WorkspaceEndToEndTest.totp(secret, Instant.now())));
        assertThat(confirm.status()).as(confirm.raw().body()).isEqualTo(200);
        confirm.body().path("recovery_codes").forEach(c -> SECRETS.add(c.asString()));
        return adopt(confirm);
    }

    private static String inviteToken(String email) throws Exception {
        String found = null;
        for (String line : Files.readAllLines(devOutbox)) {
            JsonNode n = JSON.readTree(line);
            if (n.path("to").asString().equalsIgnoreCase(email)) {
                found = n.path("link").asString();
            }
        }
        assertThat(found).isNotNull();
        String token = found.substring(found.indexOf("#token=") + 7);
        SECRETS.add(token);
        return token;
    }

    private static Login joinAs(Login owner, String space, String role) throws Exception {
        String memberEmail = email();
        Login member = registerAndLogin(memberEmail);
        Resp invited = post("/v1/spaces/" + space + "/invitations", owner.token(), csrf(owner), Map.of("email", memberEmail, "role", role));
        assertThat(invited.status()).as(invited.raw().body()).isEqualTo(201);
        Resp accepted = post("/v1/invitations/accept", member.token(), csrf(member), Map.of("token", inviteToken(memberEmail)));
        assertThat(accepted.status()).as(accepted.raw().body()).isEqualTo(200);
        return member;
    }

    @Test
    void aBusinessRunsClientsInvoicesPaymentsAndSummariesThroughTheGateway() throws Exception {
        Login owner = mfaUser();
        Resp created = post("/v1/workspaces", owner.token(), csrf(owner), Map.of("name", "E2E Builders"));
        String space = created.body().path("id").asString();
        String base = "/v1/spaces/" + space;

        assertThat(get(base + "/clients", null, null).status()).as("no session").isEqualTo(401);
        assertThat(get(base + "/clients", owner.token(), null).body().path("items")).isEmpty();
        Map<String, Object> client = Map.of("name", "E2E Client");
        assertThat(post(base + "/clients", owner.token(), Map.of("Idempotency-Key", "c0"), client).status()).as("CSRF required").isEqualTo(403);
        Resp c = post(base + "/clients", owner.token(), write(owner, "c1"), client);
        assertThat(c.status()).as(c.raw().body()).isEqualTo(201);
        String clientId = c.body().path("id").asString();

        Map<String, Object> invoice = Map.of("client_id", clientId, "invoice_date", "2026-03-01", "currency", "INR",
                "lines", List.of(Map.of("description", "E2E private line", "quantity", "3", "unit_price", "33.33", "discount", "10.00", "tax_percent", "18")));
        Resp draft = post(base + "/invoices", owner.token(), write(owner, "i1"), invoice);
        assertThat(draft.status()).as(draft.raw().body()).isEqualTo(201);
        assertThat(draft.body().path("total").asString()).isEqualTo("106.19");
        String invoiceId = draft.body().path("id").asString();
        Map<String, Object> issueBody = Map.of("confirm", true, "expected_total", "106.19");
        assertThat(post(base + "/invoices/" + invoiceId + "/issue", owner.token(), csrf(owner), issueBody).status()).as("an Idempotency-Key is required to issue").isEqualTo(400);
        Resp issued = post(base + "/invoices/" + invoiceId + "/issue", owner.token(), write(owner, "i1-issue"), issueBody);
        Resp replay = post(base + "/invoices/" + invoiceId + "/issue", owner.token(), write(owner, "i1-issue"), issueBody);
        assertThat(issued.body().path("number").asString()).isEqualTo("INV-0001");
        assertThat(replay.body().path("number").asString()).isEqualTo("INV-0001");
        assertThat(replay.raw().headers().firstValue("Idempotency-Replayed")).contains("true");

        Map<String, Object> payment = Map.of("client_id", clientId, "payment_date", "2026-03-10", "amount", "6.19", "currency", "INR", "method", "UPI",
                "reference", "E2E-UTR-secret", "allocations", List.of(Map.of("invoice_id", invoiceId, "amount", "6.19")));
        Resp paid = post(base + "/payments", owner.token(), write(owner, "p1"), payment);
        assertThat(paid.status()).as(paid.raw().body()).isEqualTo(201);
        Resp tooMuch = post(base + "/payments", owner.token(), write(owner, "p2"),
                Map.of("client_id", clientId, "payment_date", "2026-03-10", "amount", "500.00", "currency", "INR", "method", "UPI", "allocations", List.of(Map.of("invoice_id", invoiceId, "amount", "100.01"))));
        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(tooMuch.body().path("code").asString()).isEqualTo("ALLOCATION_EXCEEDS");
        JsonNode inv = get(base + "/invoices/" + invoiceId, owner.token(), null).body();
        assertThat(inv.path("paid").asString()).isEqualTo("6.19");
        assertThat(inv.path("outstanding").asString()).isEqualTo("100.00");
        assertThat(inv.path("display_status").asString()).isIn("PARTIALLY_PAID", "OVERDUE");

        Resp expense = post(base + "/expenses", owner.token(), write(owner, "e1"),
                Map.of("expense_date", "2026-03-02", "amount", "40.00", "currency", "INR", "description", "E2E materials"));
        assertThat(expense.status()).as(expense.raw().body()).isEqualTo(201);
        JsonNode overview = get(base + "/business-summaries/overview?from=2026-03-01&to=2026-03-31", owner.token(), null).body().path("currencies").get(0);
        assertThat(overview.path("invoiced_net").asString()).isEqualTo("89.99");
        assertThat(overview.path("received").asString()).isEqualTo("6.19");
        assertThat(overview.path("expenses").asString()).isEqualTo("40.00");
        assertThat(overview.path("operating_result").asString()).isEqualTo("49.99");
        assertThat(send("PUT", base + "/business-settings", owner.token(), csrf(owner), "{\"invoice_prefix\":\"E2E-\"}").body().path("next_invoice_number_preview").asString()).isEqualTo("E2E-0002");
    }

    @Test
    void rolesAndWorkspacesAreEnforcedByTheRealMembershipChain() throws Exception {
        Login owner = mfaUser();
        String space = post("/v1/workspaces", owner.token(), csrf(owner), Map.of("name", "E2E Roles")).body().path("id").asString();
        String base = "/v1/spaces/" + space;
        String clientId = post(base + "/clients", owner.token(), write(owner, "rc"), Map.of("name", "Private E2E client")).body().path("id").asString();

        Login accountant = joinAs(owner, space, "ACCOUNTANT");
        Login viewer = joinAs(owner, space, "VIEWER");
        assertThat(get(base + "/clients", accountant.token(), null).status()).isEqualTo(200);
        assertThat(post(base + "/clients", accountant.token(), write(accountant, "ac"), Map.of("name", "By accountant")).status()).isEqualTo(201);
        Resp denied = get(base + "/clients", viewer.token(), null);
        assertThat(denied.status()).as("a Viewer has no business-finance access").isEqualTo(403);
        assertThat(denied.raw().body()).doesNotContain("Private E2E client");
        assertThat(post(base + "/invoices", viewer.token(), write(viewer, "vi"), Map.of("client_id", clientId, "lines", List.of())).status()).isEqualTo(403);
        assertThat(get(base + "/business-summaries/overview", viewer.token(), null).status()).isEqualTo(403);

        Login stranger = registerAndLogin(email());
        Resp unrelated = get(base + "/clients", stranger.token(), Map.of("X-Tenant-Id", space, "X-User-Role", "OWNER"));
        assertThat(unrelated.status()).as("not a member").isEqualTo(404);
        assertThat(unrelated.raw().body()).doesNotContain("Private E2E client");

        // The owner's personal profile is refused by the business API, and the business workspace by the personal API.
        String personal = null;
        for (JsonNode w : get("/v1/workspaces", owner.token(), null).body().path("items")) {
            if ("PERSONAL".equals(w.path("type").asString())) {
                personal = w.path("id").asString();
            }
        }
        assertThat(get("/v1/spaces/" + personal + "/clients", owner.token(), null).status()).isEqualTo(409);
        assertThat(get(base + "/accounts", owner.token(), null).status()).isEqualTo(409);
        assertThat(get("/v1/spaces/" + UUID.randomUUID() + "/clients", owner.token(), null).status()).isEqualTo(404);
    }

    @Test
    void businessLogsHoldNoNamesAmountsNotesOrSecrets() throws Exception {
        Login owner = mfaUser();
        String space = post("/v1/workspaces", owner.token(), csrf(owner), Map.of("name", "E2E Logs")).body().path("id").asString();
        String base = "/v1/spaces/" + space;
        String clientId = post(base + "/clients", owner.token(), write(owner, "lc"), Map.of("name", "e2e-log-client-xyz", "email", "e2e-log@example.test")).body().path("id").asString();
        post(base + "/expenses", owner.token(), write(owner, "le"), Map.of("expense_date", "2026-03-02", "amount", "8888.88", "currency", "INR", "description", "e2e-log-note-xyz"));
        post(base + "/payments", owner.token(), write(owner, "lp"), Map.of("client_id", clientId, "payment_date", "2026-03-02", "amount", "9999.99", "currency", "INR",
                "method", "CASH", "reference", "e2e-log-ref-xyz"));
        Thread.sleep(300);
        String logs = Files.readString(logDir.resolve("finance.log")) + Files.readString(logDir.resolve("api-gateway.log"));
        assertThat(logs).doesNotContain("e2e-log-client-xyz").doesNotContain("e2e-log@example.test").doesNotContain("e2e-log-note-xyz").doesNotContain("e2e-log-ref-xyz")
                .doesNotContain("8888.88").doesNotContain("9999.99");
        assertThat(logs).doesNotContain(owner.token()).doesNotContain(owner.csrf());
    }
}
