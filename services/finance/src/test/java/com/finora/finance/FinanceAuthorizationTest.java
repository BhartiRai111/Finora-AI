package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.testkit.TestTokens;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;

/** Who may see and change what: profile isolation, scopes, roles, audit and database-level guarantees. */
class FinanceAuthorizationTest extends FinanceTestSupport {

    private String stripIds(Resp r) {
        return r.raw().body().replaceAll("\"request_id\":\"[^\"]+\"", "");
    }

    /** A profile with one of everything. */
    private record Data(String account, String category, String tx, String budget, String goal, String bill, String rule, String tag) {
    }

    private Data populate(Person p) throws Exception {
        String account = bank(p, "Main", "100.00");
        String category = category(p, "Food", "EXPENSE");
        String tx = ok(tx(p, "EXPENSE", account, "10.00", "2026-03-02", category, "secret-tag"));
        String budget = ok(post(p, "/budgets", Map.of("category_id", category, "amount", "50.00", "currency", "INR")));
        String goal = ok(post(p, "/goals", Map.of("name", "Private goal", "target_amount", "10.00", "currency", "INR")));
        String bill = ok(post(p, "/bills", Map.of("name", "Rent", "amount", "10.00", "currency", "INR", "frequency", "ONCE", "first_due_date", "2026-03-20")));
        String rule = ok(post(p, "/recurring-rules", Map.of("kind", "EXPENSE", "account_id", account, "amount", "1.00", "frequency", "MONTHLY", "start_date", "2026-04-01")));
        String tag = get(p, "/tags").body().path("items").get(0).path("id").asString();
        return new Data(account, category, tx, budget, goal, bill, rule, tag);
    }

    @Test
    void anotherProfilesRecordsLookExactlyLikeRecordsThatDoNotExist() throws Exception {
        Person a = person();
        Person b = person();
        Data d = populate(a);
        String missing = UUID.randomUUID().toString();
        Map<String, String> paths = new java.util.LinkedHashMap<>();
        paths.put("/accounts/", d.account());
        paths.put("/transactions/", d.tx());
        paths.put("/goals/", d.goal());
        paths.put("/bills/", d.bill());
        for (Map.Entry<String, String> e : paths.entrySet()) {
            Resp foreign = get(b, e.getKey() + e.getValue());
            Resp nothing = get(b, e.getKey() + missing);
            assertThat(foreign.status()).as(e.getKey()).isEqualTo(404);
            assertThat(stripIds(foreign)).as(e.getKey()).isEqualTo(stripIds(nothing));
        }
        for (String list : List.of("/accounts", "/transactions", "/categories", "/tags", "/goals", "/bills", "/subscriptions", "/recurring-rules")) {
            Resp r = get(b, list);
            assertThat(r.status()).as(list).isEqualTo(200);
            assertThat(r.body().path("items")).as(list + " must not leak").isEmpty();
        }
        assertThat(get(b, "/budgets").body().path("items")).isEmpty();
        assertThat(get(b, "/net-worth").body().path("currencies")).isEmpty();
        assertThat(get(b, "/summaries/cashflow").body().path("currencies")).isEmpty();
        assertThat(get(b, "/transactions?tags=secret-tag").body().path("items")).isEmpty();
        assertThat(get(b, "/transactions?account_id=" + d.account()).body().path("items")).isEmpty();
        assertThat(get(b, "/transactions?category_id=" + d.category()).body().path("items")).isEmpty();
    }

    @Test
    void anotherProfilesRecordsCannotBeChangedOrReferenced() throws Exception {
        Person a = person();
        Person b = person();
        Data d = populate(a);
        String bAccount = bank(b, "Mine", "50.00");
        assertThat(patch(b, "/transactions/" + d.tx(), Map.of("amount", "1.00", "version", 0)).status()).isEqualTo(404);
        assertThat(post(b, "/transactions/" + d.tx() + "/void", Map.of("reason", "x")).status()).isEqualTo(404);
        assertThat(post(b, "/transactions/" + d.tx() + "/restore", Map.of()).status()).isEqualTo(404);
        assertThat(post(b, "/transactions/" + d.tx() + "/confirm", Map.of()).status()).isEqualTo(404);
        assertThat(patch(b, "/accounts/" + d.account(), Map.of("name", "Hacked", "version", 0)).status()).isEqualTo(404);
        assertThat(post(b, "/accounts/" + d.account() + "/archive", Map.of()).status()).isEqualTo(404);
        assertThat(post(b, "/accounts/" + d.account() + "/adjustments", Map.of("new_balance", "1.00")).status()).isEqualTo(404);
        assertThat(patch(b, "/budgets/" + d.budget(), Map.of("amount", "1.00", "version", 0)).status()).isEqualTo(404);
        assertThat(post(b, "/budgets/" + d.budget() + "/archive", Map.of()).status()).isEqualTo(404);
        assertThat(post(b, "/goals/" + d.goal() + "/contributions", Map.of("amount", "1.00")).status()).isEqualTo(404);
        assertThat(post(b, "/bills/" + d.bill() + "/pay", Map.of("account_id", bAccount)).status()).isEqualTo(404);
        assertThat(patch(b, "/recurring-rules/" + d.rule(), Map.of("amount", "9.00", "version", 0)).status()).isEqualTo(404);
        assertThat(post(b, "/recurring-rules/" + d.rule() + "/pause", Map.of()).status()).isEqualTo(404);
        assertThat(patch(b, "/tags/" + d.tag(), Map.of("name", "stolen")).status()).isEqualTo(404);
        assertThat(raw("POST", base(b) + "/categories/" + d.category() + "/archive", token(b), null).status()).isEqualTo(404);
        // Referencing a foreign record from a body behaves exactly like referencing one that does not exist.
        Resp foreignAccount = tx(b, "EXPENSE", d.account(), "1.00", "2026-03-02", null);
        Resp missingAccount = tx(b, "EXPENSE", UUID.randomUUID().toString(), "1.00", "2026-03-02", null);
        assertThat(foreignAccount.status()).isEqualTo(422);
        assertThat(stripIds(foreignAccount)).isEqualTo(stripIds(missingAccount));
        assertThat(tx(b, "EXPENSE", bAccount, "1.00", "2026-03-02", d.category()).status()).isEqualTo(422);
        assertThat(post(b, "/transactions", Map.of("kind", "REFUND", "account_id", bAccount, "amount", "1.00", "occurred_on", "2026-03-02", "refund_of", d.tx())).status()).isEqualTo(422);
        assertThat(post(b, "/transfers", Map.of("from_account_id", bAccount, "to_account_id", d.account(), "amount", "1.00", "occurred_on", "2026-03-02")).status()).isEqualTo(422);
        assertThat(post(b, "/budgets", Map.of("category_id", d.category(), "amount", "1.00", "currency", "INR")).status()).isEqualTo(422);
        assertThat(post(b, "/recurring-rules", Map.of("kind", "EXPENSE", "account_id", d.account(), "amount", "1.00", "frequency", "MONTHLY", "start_date", "2026-04-01")).status()).isEqualTo(422);
        assertThat(raw("POST", base(b) + "/categories/" + d.category() + "/merge", token(b), Map.of("into_category_id", category(b, "Mine", "EXPENSE"))).status()).isEqualTo(404);
        // Nothing of A's changed.
        assertThat(balance(a, d.account())).isEqualTo("90.00");
        assertThat(get(a, "/transactions/" + d.tx()).text("status")).isEqualTo("POSTED");
        assertThat(get(a, "/goals/" + d.goal()).text("saved")).isEqualTo("0.00");
    }

    @Test
    void aTokenForOneProfileCannotBeUsedOnAnothersPath() throws Exception {
        Person a = person();
        Person b = person();
        populate(a);
        Resp r = raw("GET", base(a) + "/accounts", token(b), null);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.header("X-Internal-Auth")).isEqualTo("rejected");
        assertThat(raw("POST", base(a) + "/transactions", token(b), Map.of("kind", "EXPENSE"), "Idempotency-Key", "k").status()).isEqualTo(404);
    }

    @Test
    void businessWorkspacesAndUnknownScopesGetNoPersonalFinance() throws Exception {
        Person p = person();
        bank(p, "Main", "10.00");
        for (List<String> scopes : List.of(List.of("workspace:business"), List.<String>of(), List.of("workspace:unknown"), List.of("something:else"))) {
            String t = token(p, p.space(), "OWNER", scopes);
            for (String path : List.of("/accounts", "/transactions", "/net-worth", "/budgets", "/goals", "/finance-settings")) {
                Resp r = raw("GET", base(p) + path, t, null);
                assertThat(r.status()).as(scopes + " " + path).isEqualTo(409);
                assertThat(r.raw().body()).doesNotContain("Main");
            }
            assertThat(raw("POST", base(p) + "/accounts", t, Map.of("name", "X", "type", "CASH", "currency", "INR")).status()).isEqualTo(409);
        }
    }

    @Test
    void onlyThePersonalOwnerMayUseTheService() throws Exception {
        Person p = person();
        bank(p, "Main", "10.00");
        for (String role : List.of("ADMIN", "ACCOUNTANT", "MANAGER", "CONTRIBUTOR", "AUDITOR", "VIEWER", "NOT_A_ROLE")) {
            String t = token(p, p.space(), role, List.of("workspace:personal"));
            assertThat(raw("GET", base(p) + "/accounts", t, null).status()).as(role).isEqualTo(403);
            assertThat(raw("GET", base(p) + "/summaries/cashflow", t, null).status()).as(role).isEqualTo(403);
            assertThat(raw("POST", base(p) + "/transactions", t, Map.of("kind", "EXPENSE"), "Idempotency-Key", "k").status()).as(role).isEqualTo(403);
        }
        assertThat(get(p, "/accounts").status()).isEqualTo(200);
    }

    @Test
    void anonymousServiceAndForgedCallersAreRefused() throws Exception {
        Person p = person();
        String path = base(p) + "/accounts";
        assertThat(raw("GET", path, TestTokens.issuer(TestTokens.GATEWAY, Duration.ofMinutes(2), clock).issueAnonymous("finance"), null).status()).isEqualTo(401);
        assertThat(raw("GET", path, TestTokens.issuer(TestTokens.CALLER, Duration.ofMinutes(2), clock).issueService("finance", null), null).status()).isEqualTo(403);
        assertThat(raw("GET", path, "garbage", null).status()).isEqualTo(401);
        assertThat(raw("GET", path, TestTokens.issuer(TestTokens.GATEWAY, Duration.ofMinutes(2), clock).issueUser("identity-access", p.userId(), UUID.randomUUID(),
                p.space(), null, "OWNER", List.of("workspace:personal"), null), null).status()).as("wrong audience").isEqualTo(401);
        assertThat(raw("GET", path, TestTokens.issuer("attacker", Duration.ofMinutes(2), clock).issueUser("finance", p.userId(), UUID.randomUUID(),
                p.space(), null, "OWNER", List.of("workspace:personal"), null), null).status()).as("unknown issuer").isEqualTo(401);
        // Client-supplied identity headers change nothing.
        assertThat(raw("GET", path, token(p), null, "X-Tenant-Id", UUID.randomUUID().toString(), "X-User-Role", "OWNER", "X-Workspace-Id", UUID.randomUUID().toString()).status()).isEqualTo(200);
    }

    @Test
    void importantChangesAreAuditedWithTheActorAndNeverEditableByTheService() throws Exception {
        Person p = person();
        Data d = populate(p);
        patch(p, "/transactions/" + d.tx(), Map.of("amount", "11.00", "version", 0));
        post(p, "/transactions/" + d.tx() + "/void", Map.of("reason", "test"));
        List<String> types = jdbc.sql("select event_type from finance_audit_events where tenant_id = :t").param("t", p.space()).query(String.class).list();
        assertThat(types).contains("ACCOUNT_CREATED", "CATEGORY_CREATED", "TRANSACTION_CREATED", "TRANSACTION_EDITED", "TRANSACTION_VOIDED",
                "BUDGET_CREATED", "GOAL_CREATED", "BILL_CREATED", "RECURRING_CREATED");
        assertThat(jdbc.sql("select count(distinct actor_id) from finance_audit_events where tenant_id = :t").param("t", p.space()).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("select actor_id from finance_audit_events where tenant_id = :t limit 1").param("t", p.space()).query(UUID.class).single()).isEqualTo(p.userId());
        assertThatThrownBy(() -> jdbc.sql("update finance_audit_events set event_type = 'X' where tenant_id = :t").param("t", p.space()).update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("delete from finance_audit_events where tenant_id = :t").param("t", p.space()).update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("truncate finance_audit_events").update()).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void theDatabaseItselfRefusesCrossProfileReferencesAndMalformedRows() throws Exception {
        Person a = person();
        Person b = person();
        String aAccount = bank(a, "A", "1.00");
        String bAccount = bank(b, "B", "1.00");
        String insert = "insert into transactions (id, tenant_id, kind, status, account_id, amount, currency, occurred_on, created_by, created_at, updated_at) "
                + "values (:id, :t, 'EXPENSE', 'POSTED', :a, :amt, 'INR', '2026-03-01', :u, now(), now())";
        assertThatThrownBy(() -> jdbc.sql(insert).param("id", UUID.randomUUID()).param("t", b.space()).param("a", UUID.fromString(aAccount))
                .param("amt", new java.math.BigDecimal("1.00")).param("u", b.userId()).update()).hasMessageContaining("transactions_account_fk");
        assertThatThrownBy(() -> jdbc.sql(insert).param("id", UUID.randomUUID()).param("t", b.space()).param("a", UUID.fromString(bAccount))
                .param("amt", new java.math.BigDecimal("0.00")).param("u", b.userId()).update()).hasMessageContaining("transactions_amount_chk");
        assertThatThrownBy(() -> jdbc.sql("insert into transactions (id, tenant_id, kind, status, account_id, to_account_id, amount, currency, occurred_on, created_by, created_at, updated_at) "
                + "values (:id, :t, 'TRANSFER', 'POSTED', :a, :a, 1, 'INR', '2026-03-01', :u, now(), now())")
                .param("id", UUID.randomUUID()).param("t", b.space()).param("a", UUID.fromString(bAccount)).param("u", b.userId()).update())
                .hasMessageContaining("transactions_transfer_");
        assertThatThrownBy(() -> jdbc.sql("insert into transactions (id, tenant_id, kind, status, account_id, amount, currency, occurred_on, created_by, created_at, updated_at) "
                + "values (:id, :t, 'REFUND', 'POSTED', :a, 1, 'INR', '2026-03-01', :u, now(), now())")
                .param("id", UUID.randomUUID()).param("t", b.space()).param("a", UUID.fromString(bAccount)).param("u", b.userId()).update())
                .hasMessageContaining("transactions_refund_chk");
        assertThatThrownBy(() -> jdbc.sql("insert into transactions (id, tenant_id, kind, status, account_id, amount, currency, occurred_on, created_by, created_at, updated_at) "
                + "values (:id, :t, 'EXPENSE', 'POSTED', :a, 1, 'inr', '2026-03-01', :u, now(), now())")
                .param("id", UUID.randomUUID()).param("t", b.space()).param("a", UUID.fromString(bAccount)).param("u", b.userId()).update())
                .hasMessageContaining("transactions_currency_chk");
        assertThatThrownBy(() -> jdbc.sql("insert into budgets (id, tenant_id, category_id, amount, currency, created_at, updated_at) values (:id, :t, :c, 1, 'INR', now(), now())")
                .param("id", UUID.randomUUID()).param("t", b.space()).param("c", UUID.randomUUID()).update()).hasMessageContaining("budgets_category_fk");
    }

    @Test
    void errorsAreSafeAndSecretsStayOutOfTheLogs(CapturedOutput output) throws Exception {
        Person p = person();
        String a = bank(p, "Main", "10.00");
        Resp bad = tx(p, "EXPENSE", a, "abc", "2026-03-01", null);
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.header("Content-Type")).startsWith("application/problem+json");
        assertThat(bad.raw().body()).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("SQL").doesNotContain("postgres");
        assertThat(bad.body().has("request_id")).isTrue();
        Resp broken = raw("POST", base(p) + "/transactions", token(p), "{\"kind\": [", "Idempotency-Key", "k");
        assertThat(broken.status()).isEqualTo(400);
        assertThat(broken.raw().body()).doesNotContain("jackson").doesNotContain("Exception");
        assertThat(raw("DELETE", base(p) + "/accounts/" + a, token(p), null).status()).as("no hard deletes exist").isIn(404, 405);
        ok(post(p, "/transactions", Map.of("kind", "EXPENSE", "account_id", a, "amount", "1.00", "occurred_on", "2026-03-01", "note", "very-private-note-text", "payee", "very-private-payee")));
        assertThat(output.getAll()).doesNotContain("very-private-note-text").doesNotContain("very-private-payee");
    }
}
