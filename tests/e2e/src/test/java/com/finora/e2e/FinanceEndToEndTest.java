package com.finora.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Personal finance through the real stack: browser-style session cookie and CSRF token -> gateway -> real identity-access
 * (session and membership) -> real finance service -> PostgreSQL. No authentication is faked.
 */
class FinanceEndToEndTest extends E2eBase {

    private static Map<String, String> csrf(Login l) {
        return Map.of("X-CSRF-Token", l.csrf());
    }

    private static Map<String, String> write(Login l, String key) {
        return Map.of("X-CSRF-Token", l.csrf(), "Idempotency-Key", key);
    }

    private static String personalSpace(Login l) throws Exception {
        for (JsonNode w : get("/v1/workspaces", l.token(), null).body().path("items")) {
            if ("PERSONAL".equals(w.path("type").asString())) {
                return w.path("id").asString();
            }
        }
        throw new AssertionError("no personal profile");
    }

    @Test
    void aSignedInUserRunsTheirPersonalFinanceThroughTheGateway() throws Exception {
        Login u = registerAndLogin(email());
        String space = personalSpace(u);
        String base = "/v1/spaces/" + space;

        assertThat(get(base + "/accounts", null, null).status()).as("no session").isEqualTo(401);
        assertThat(get(base + "/accounts", "garbage", null).status()).isEqualTo(401);
        Resp empty = get(base + "/accounts", u.token(), null);
        assertThat(empty.status()).as(empty.raw().body()).isEqualTo(200);
        assertThat(empty.body().path("items")).isEmpty();

        Map<String, Object> account = Map.of("name", "Main", "type", "BANK", "currency", "INR", "opening_balance", "1000.00", "opening_date", "2026-01-01");
        assertThat(post(base + "/accounts", u.token(), Map.of("Idempotency-Key", "a"), account).status()).as("CSRF required").isEqualTo(403);
        Resp created = post(base + "/accounts", u.token(), write(u, UUID.randomUUID().toString()), account);
        assertThat(created.status()).as(created.raw().body()).isEqualTo(201);
        String accountId = created.body().path("id").asString();

        Map<String, Object> expense = Map.of("kind", "EXPENSE", "account_id", accountId, "amount", "250.75", "occurred_on", "2026-03-01");
        assertThat(post(base + "/transactions", u.token(), csrf(u), expense).status()).as("the Idempotency-Key header is required").isEqualTo(400);
        Resp first = post(base + "/transactions", u.token(), write(u, "tx-1"), expense);
        Resp retry = post(base + "/transactions", u.token(), write(u, "tx-1"), expense);
        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.body().path("id").asString()).as("a retry through the gateway returns the first result").isEqualTo(first.body().path("id").asString());
        assertThat(retry.raw().headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(get(base + "/accounts/" + accountId, u.token(), null).body().path("balance").asString()).isEqualTo("749.25");

        Resp savings = post(base + "/accounts", u.token(), write(u, "acc-2"),
                Map.of("name", "Savings", "type", "BANK", "currency", "INR", "opening_balance", "0", "opening_date", "2026-01-01"));
        Resp transfer = post(base + "/transfers", u.token(), write(u, "tr-1"),
                Map.of("from_account_id", accountId, "to_account_id", savings.body().path("id").asString(), "amount", "200.00", "occurred_on", "2026-03-02"));
        assertThat(transfer.status()).as(transfer.raw().body()).isEqualTo(201);
        JsonNode worth = get(base + "/net-worth", u.token(), null).body().path("currencies").get(0);
        assertThat(worth.path("net_worth").asString()).as("a transfer does not change net worth").isEqualTo("749.25");

        Resp budget = post(base + "/budgets", u.token(), csrf(u), Map.of("amount", "500.00", "currency", "INR"));
        assertThat(budget.status()).isEqualTo(201);
        JsonNode summary = get(base + "/budgets?month=2026-03", u.token(), null).body();
        assertThat(summary.path("items").get(0).path("spent").asString()).isEqualTo("250.75");
        assertThat(get(base + "/summaries/cashflow?from=2026-03-01&to=2026-03-31", u.token(), null).body().path("currencies").get(0)
                .path("gross_expense").asString()).isEqualTo("250.75");
        assertThat(get(base + "/finance-settings", u.token(), null).body().path("timezone").asString()).isEqualTo("UTC");
        assertThat(send("PUT", base + "/finance-settings", u.token(), csrf(u), "{\"timezone\":\"Asia/Kolkata\"}").body().path("timezone").asString()).isEqualTo("Asia/Kolkata");
    }

    @Test
    void otherUsersAndBusinessWorkspacesCannotReachPersonalFinance() throws Exception {
        Login owner = registerAndLogin(email());
        Login other = registerAndLogin(email());
        String space = personalSpace(owner);
        post("/v1/spaces/" + space + "/accounts", owner.token(), write(owner, "k1"),
                Map.of("name", "Private", "type", "CASH", "currency", "INR", "opening_balance", "5.00"));

        Resp denied = get("/v1/spaces/" + space + "/accounts", other.token(), Map.of("X-Tenant-Id", space, "X-User-Id", owner.userId(), "X-User-Role", "OWNER"));
        assertThat(denied.status()).as("not a member of that profile").isEqualTo(404);
        assertThat(denied.raw().body()).doesNotContain("Private");
        assertThat(post("/v1/spaces/" + space + "/transactions", other.token(), write(other, "k2"),
                Map.of("kind", "EXPENSE", "account_id", UUID.randomUUID().toString(), "amount", "1.00", "occurred_on", "2026-03-01")).status()).isEqualTo(404);

        Resp business = post("/v1/workspaces", owner.token(), csrf(owner), Map.of("name", "Acme"));
        assertThat(business.status()).isEqualTo(201);
        Resp viaBusiness = get("/v1/spaces/" + business.body().path("id").asString() + "/accounts", owner.token(), null);
        assertThat(viaBusiness.status()).as("personal finance does not run in a business workspace").isEqualTo(409);
        assertThat(viaBusiness.raw().body()).doesNotContain("Private");

        assertThat(get("/internal/v1/ping", owner.token(), null).status()).isEqualTo(404);
        assertThat(get("/v1/spaces/" + UUID.randomUUID() + "/accounts", owner.token(), null).status()).as("a space they do not belong to").isEqualTo(404);
    }

    @Test
    void financeLogsHoldNoAmountsNotesOrSecrets() throws Exception {
        Login u = registerAndLogin(email());
        String space = personalSpace(u);
        String account = post("/v1/spaces/" + space + "/accounts", u.token(), write(u, "log-a"),
                Map.of("name", "Logged", "type", "CASH", "currency", "INR", "opening_balance", "1.00")).body().path("id").asString();
        post("/v1/spaces/" + space + "/transactions", u.token(), write(u, "log-t"), Map.of("kind", "EXPENSE", "account_id", account, "amount", "7777.77",
                "occurred_on", "2026-03-01", "note", "e2e-private-note-xyz", "payee", "e2e-private-payee-xyz"));
        Thread.sleep(300);
        String logs = java.nio.file.Files.readString(logDir.resolve("finance.log")) + java.nio.file.Files.readString(logDir.resolve("api-gateway.log"));
        assertThat(logs).doesNotContain("e2e-private-note-xyz").doesNotContain("e2e-private-payee-xyz").doesNotContain("7777.77");
        assertThat(logs).doesNotContain(u.token()).doesNotContain(u.csrf());
    }
}
