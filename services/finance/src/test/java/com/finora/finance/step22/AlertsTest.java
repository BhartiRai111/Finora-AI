package com.finora.finance.step22;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.finance.accounting.AccountingService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Alert triggers, thresholds, deduplication, resolution, lifecycle, permissions and isolation. Today is 2026-03-15 (last complete month is February). */
class AlertsTest extends Step22Support {

    @Autowired AccountingService accounting;

    private JsonNode evaluate(Person p) throws Exception {
        Resp r = post(p, "/finance-alerts/evaluate", Map.of());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    private JsonNode evaluate(Biz b, UUID user, String role) throws Exception {
        Resp r = as(b, user, role, "POST", "/finance-alerts/evaluate", Map.of());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    private int created(JsonNode e, String type) {
        return e.path("created").path(type).asInt(-1);
    }

    private JsonNode alerts(Person p, String query) throws Exception {
        return get(p, "/finance-alerts" + query).body();
    }

    private JsonNode alerts(Biz b, String query) throws Exception {
        return bget(b, "/finance-alerts" + query).body();
    }

    private void put(Person p, String type, Map<String, Object> body) throws Exception {
        Resp r = raw("PUT", base(p) + "/finance-alerts/settings/" + type, token(p), body);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ personal alerts

    @Test
    void aBudgetAlertFiresAtTheThresholdOncePerMonthAndLevelThenEscalatesAndResolvesTheWarning() throws Exception {
        Person p = person();
        String acc = account(p, "Main", "BANK", "INR", "0.00", "2025-01-01");
        String food = category(p, "Food", "EXPENSE");
        ok(post(p, "/budgets", Map.of("category_id", food, "amount", "100.00", "currency", "INR")));
        ok(tx(p, "EXPENSE", acc, "85.00", "2026-03-05", food));
        JsonNode first = evaluate(p);
        assertThat(created(first, "BUDGET_THRESHOLD")).isEqualTo(1);
        JsonNode a = alerts(p, "?type=BUDGET_THRESHOLD").path("items").get(0);
        assertThat(a.path("severity").asString()).isEqualTo("WARNING");
        assertThat(a.path("status").asString()).isEqualTo("OPEN");
        assertThat(a.path("title").asString()).contains("Food").contains("85.00%");
        assertThat(a.path("details").path("budget").asString()).isEqualTo("100.00");
        assertThat(a.path("details").path("spent").asString()).isEqualTo("85.00");
        assertThat(a.path("details").path("warn_percent").asString()).isEqualTo("80");
        assertThat(a.path("generated_at").asString()).startsWith("2026-03-15");
        assertThat(created(evaluate(p), "BUDGET_THRESHOLD")).as("the same window never alerts twice").isZero();
        assertThat(count("select count(*) from notifications where tenant_id = :p0 and category = 'ALERT'", p.space())).isEqualTo(1);
        ok(tx(p, "EXPENSE", acc, "20.00", "2026-03-10", food));
        JsonNode second = evaluate(p);
        assertThat(created(second, "BUDGET_THRESHOLD")).isEqualTo(1);
        assertThat(second.path("resolved").path("BUDGET_THRESHOLD").asInt()).as("the warning no longer describes the situation").isEqualTo(1);
        JsonNode all = alerts(p, "?type=BUDGET_THRESHOLD&limit=10");
        assertThat(all.path("total").asLong()).isEqualTo(2);
        assertThat(alerts(p, "?status=OPEN").path("items").get(0).path("severity").asString()).isEqualTo("CRITICAL");
        assertThat(alerts(p, "?status=RESOLVED").path("items").get(0).path("resolved_reason").asString()).isEqualTo("The condition no longer holds");
        Person quiet = person();
        String qa = account(quiet, "Main", "BANK", "INR", "0.00", "2025-01-01");
        String qf = category(quiet, "Food", "EXPENSE");
        ok(post(quiet, "/budgets", Map.of("category_id", qf, "amount", "100.00", "currency", "INR")));
        ok(tx(quiet, "EXPENSE", qa, "85.00", "2026-03-05", qf));
        put(quiet, "BUDGET_THRESHOLD", Map.of("threshold", "90"));
        assertThat(created(evaluate(quiet), "BUDGET_THRESHOLD")).as("a stricter configured threshold").isZero();
    }

    @Test
    void aSpendingIncreaseAlertNeedsActivityInBothMonthsAndRespectsTheConfiguredPercent() throws Exception {
        Person p = person();
        String acc = account(p, "Main", "BANK", "INR", "0.00", "2025-01-01");
        ok(tx(p, "EXPENSE", acc, "100.00", "2026-01-10", null));
        assertThat(created(evaluate(p), "SPENDING_CHANGE")).as("only one month has activity: nothing to compare").isZero();
        ok(tx(p, "EXPENSE", acc, "200.00", "2026-02-10", null));
        assertThat(created(evaluate(p), "SPENDING_CHANGE")).isEqualTo(1);
        JsonNode a = alerts(p, "?type=SPENDING_CHANGE").path("items").get(0);
        assertThat(a.path("details").path("change_percent").asString()).isEqualTo("100.00");
        assertThat(a.path("details").path("previous_amount").asString()).isEqualTo("100.00");
        assertThat(a.path("details").path("amount").asString()).isEqualTo("200.00");
        assertThat(a.path("details").path("threshold_percent").asString()).isEqualTo("30");
        assertThat(a.path("window_start").asString()).isEqualTo("2026-01-01");
        assertThat(a.path("window_end").asString()).isEqualTo("2026-02-28");
        assertThat(created(evaluate(p), "SPENDING_CHANGE")).isZero();
        Person strict = person();
        String sa = account(strict, "Main", "BANK", "INR", "0.00", "2025-01-01");
        ok(tx(strict, "EXPENSE", sa, "100.00", "2026-01-10", null));
        ok(tx(strict, "EXPENSE", sa, "200.00", "2026-02-10", null));
        put(strict, "SPENDING_CHANGE", Map.of("threshold", "150"));
        assertThat(created(evaluate(strict), "SPENDING_CHANGE")).isZero();
        Person fell = person();
        String fa = account(fell, "Main", "BANK", "INR", "0.00", "2025-01-01");
        ok(tx(fell, "EXPENSE", fa, "200.00", "2026-01-10", null));
        ok(tx(fell, "EXPENSE", fa, "100.00", "2026-02-10", null));
        assertThat(created(evaluate(fell), "SPENDING_CHANGE")).as("a decrease is not an alert").isZero();
        Person usd = person();
        String ua = account(usd, "Dollar", "BANK", "USD", "0.00", "2025-01-01");
        String ui = account(usd, "Rupee", "BANK", "INR", "0.00", "2025-01-01");
        ok(tx(usd, "EXPENSE", ua, "10.00", "2026-01-10", null));
        ok(tx(usd, "EXPENSE", ua, "20.00", "2026-02-10", null));
        ok(tx(usd, "EXPENSE", ui, "1000.00", "2026-01-10", null));
        ok(tx(usd, "EXPENSE", ui, "1000.00", "2026-02-10", null));
        evaluate(usd);
        JsonNode items = alerts(usd, "?type=SPENDING_CHANGE").path("items");
        assertThat(items).as("currencies are evaluated separately, never mixed").hasSize(1);
        assertThat(items.get(0).path("details").path("currency").asString()).isEqualTo("USD");
    }

    @Test
    void anUnusualExpenseAlertUsesTheAssistantRulesAndNeverRepeats() throws Exception {
        Person p = person();
        String acc = account(p, "Main", "BANK", "INR", "0.00", "2025-01-01");
        String dining = category(p, "Dining", "EXPENSE");
        String[] base = {"100.00", "110.00", "120.00", "105.00", "95.00", "115.00", "100.00", "110.00"};
        for (int i = 0; i < base.length; i++) {
            ok(tx(p, "EXPENSE", acc, base[i], java.time.LocalDate.parse("2025-10-01").plusDays(i * 10L).toString(), dining));
        }
        ok(tx(p, "EXPENSE", acc, "1000.00", "2026-03-12", dining));
        ok(tx(p, "EXPENSE", acc, "140.00", "2026-03-13", dining));
        JsonNode e = evaluate(p);
        assertThat(created(e, "UNUSUAL_EXPENSE")).isEqualTo(1);
        JsonNode a = alerts(p, "?type=UNUSUAL_EXPENSE").path("items").get(0);
        assertThat(a.path("details").path("rule").asString()).isEqualTo("LARGE_AMOUNT");
        assertThat(a.path("details").path("amount").asString()).isEqualTo("1000.00");
        assertThat(a.path("details").has("payee")).as("no names in alert details").isFalse();
        assertThat(created(evaluate(p), "UNUSUAL_EXPENSE")).isZero();
        Person old = person();
        String oa = account(old, "Main", "BANK", "INR", "0.00", "2025-01-01");
        String od = category(old, "Dining", "EXPENSE");
        for (int i = 0; i < base.length; i++) {
            ok(tx(old, "EXPENSE", oa, base[i], java.time.LocalDate.parse("2025-10-01").plusDays(i * 10L).toString(), od));
        }
        ok(tx(old, "EXPENSE", oa, "1000.00", "2026-02-20", od));
        assertThat(created(evaluate(old), "UNUSUAL_EXPENSE")).as("outside the 7-day look-back").isZero();
        put(old, "UNUSUAL_EXPENSE", Map.of("days", 30));
        assertThat(created(evaluate(old), "UNUSUAL_EXPENSE")).as("a longer configured look-back").isEqualTo(1);
        assertThat(created(evaluate(person()), "UNUSUAL_EXPENSE")).as("sparse history alerts nothing").isZero();
    }

    @Test
    void lowCashNeedsAnExplicitThresholdAndCurrencyAndResolvesWhenTheBalanceRecovers() throws Exception {
        Person p = person();
        String acc = account(p, "Main", "BANK", "INR", "100.00", "2025-01-01");
        JsonNode first = evaluate(p);
        assertThat(first.path("skipped").toString()).contains("LOW_CASH").contains("disabled");
        assertThat(raw("PUT", base(p) + "/finance-alerts/settings/LOW_CASH", token(p), Map.of("enabled", true)).status()).as("no assumed threshold").isEqualTo(422);
        assertThat(raw("PUT", base(p) + "/finance-alerts/settings/LOW_CASH", token(p), Map.of("enabled", true, "threshold", "0", "currency", "INR")).status()).isEqualTo(422);
        assertThat(raw("PUT", base(p) + "/finance-alerts/settings/LOW_CASH", token(p), Map.of("enabled", true, "threshold", "500", "currency", "XXX")).status()).isEqualTo(422);
        put(p, "LOW_CASH", Map.of("enabled", true, "threshold", "500", "currency", "INR"));
        assertThat(created(evaluate(p), "LOW_CASH")).isEqualTo(1);
        JsonNode a = alerts(p, "?type=LOW_CASH").path("items").get(0);
        assertThat(a.path("severity").asString()).isEqualTo("CRITICAL");
        assertThat(a.path("details").path("balance").asString()).isEqualTo("100.00");
        assertThat(a.path("details").path("threshold").asString()).isEqualTo("500.00");
        assertThat(a.path("details").path("basis").asString()).contains("recorded");
        assertThat(created(evaluate(p), "LOW_CASH")).isZero();
        ok(tx(p, "INCOME", acc, "1000.00", "2026-03-14", null));
        JsonNode e = evaluate(p);
        assertThat(e.path("resolved").path("LOW_CASH").asInt()).isEqualTo(1);
        assertThat(alerts(p, "?status=RESOLVED").path("items").get(0).path("alert_type").asString()).isEqualTo("LOW_CASH");
        put(p, "LOW_CASH", Map.of("currency", "USD"));
        assertThat(evaluate(p).path("skipped").toString()).as("no accounts in the configured currency: not enough information").contains("not enough information");
        assertThat(count("select count(*) from alerts where tenant_id = :p0 and alert_type = 'LOW_CASH'", p.space())).isEqualTo(1);
    }

    @Test
    void importAndReconciliationIssuesAreAlertedAndTheLatterResolvesWhenTheLinesAreHandled() throws Exception {
        Person p = person();
        UUID imp = UUID.randomUUID();
        jdbc.sql("insert into finance_imports (id, tenant_id, domain, kind, status, filename, sha256, size_bytes, rejected_rows, committed_rows, created_by, created_at, updated_at, completed_at) "
                + "values (:id, :t, 'PERSONAL', 'STATEMENT', 'FAILED', 'bank.csv', :sha, 10, 4, 0, :u, now(), now(), :done)").param("id", imp).param("t", p.space()).param("sha", "a".repeat(64)).param("u", p.userId())
                .param("done", java.time.OffsetDateTime.parse("2026-03-13T10:00:00Z")).update();
        UUID line = UUID.randomUUID();
        jdbc.sql("insert into finance_statement_lines (id, tenant_id, domain, import_id, row_number, line_date, amount, currency, status, created_at, updated_at) "
                + "values (:id, :t, 'PERSONAL', :i, 1, '2026-02-01', 10.00, 'INR', 'UNMATCHED', now(), now())").param("id", line).param("t", p.space()).param("i", imp).update();
        JsonNode e = evaluate(p);
        assertThat(created(e, "IMPORT_ISSUE")).isEqualTo(1);
        assertThat(created(e, "RECONCILIATION_UNRESOLVED")).isEqualTo(1);
        JsonNode importAlert = alerts(p, "?type=IMPORT_ISSUE").path("items").get(0);
        assertThat(importAlert.path("details").path("status").asString()).isEqualTo("FAILED");
        assertThat(importAlert.path("details").path("rejected_rows").asInt()).isEqualTo(4);
        assertThat(importAlert.path("details").toString()).doesNotContain("bank.csv");
        assertThat(alerts(p, "?type=RECONCILIATION_UNRESOLVED").path("items").get(0).path("details").path("unmatched_lines").asInt()).isEqualTo(1);
        assertThat(created(evaluate(p), "IMPORT_ISSUE")).isZero();
        jdbc.sql("update finance_statement_lines set status = 'IGNORED', ignore_reason = 'test' where tenant_id = :t").param("t", p.space()).update();
        assertThat(evaluate(p).path("resolved").path("RECONCILIATION_UNRESOLVED").asInt()).isEqualTo(1);
        assertThat(alerts(p, "?type=IMPORT_ISSUE&status=OPEN").path("total").asLong()).as("an event alert stays until a person handles it").isEqualTo(1);
    }

    // ------------------------------------------------------------------ business alerts

    @Test
    void receivablesAlertsFollowInvoicesAndResolveWhenPaid() throws Exception {
        Biz b = biz();
        String c = client(b, "Slow Payer");
        String overdue = ok(draft(b, c, "2026-01-05", "2026-02-05", "INR", List.of(line("Work", "1", "1000.00")), null));
        ok(issue(b, overdue, "1000.00"));
        String soon = ok(draft(b, c, "2026-03-01", "2026-03-18", "INR", List.of(line("Work", "1", "500.00")), null));
        ok(issue(b, soon, "500.00"));
        JsonNode e = evaluate(b, b.owner(), "OWNER");
        assertThat(created(e, "RECEIVABLES_OVERDUE")).isEqualTo(1);
        assertThat(created(e, "RECEIVABLES_DUE_SOON")).isEqualTo(1);
        JsonNode o = alerts(b, "?type=RECEIVABLES_OVERDUE").path("items").get(0);
        assertThat(o.path("details").path("overdue").asString()).isEqualTo("1000.00");
        assertThat(o.path("severity").asString()).isEqualTo("WARNING");
        JsonNode d = alerts(b, "?type=RECEIVABLES_DUE_SOON").path("items").get(0);
        assertThat(d.path("severity").asString()).isEqualTo("INFO");
        assertThat(d.path("details").path("due_date").asString()).isEqualTo("2026-03-18");
        assertThat(d.path("details").path("outstanding").asString()).isEqualTo("500.00");
        assertThat(created(evaluate(b, b.owner(), "OWNER"), "RECEIVABLES_OVERDUE")).isZero();
        ok(pay(b, c, "1500.00", "ref-1", List.of(alloc(overdue, "1000.00"), alloc(soon, "500.00"))));
        JsonNode after = evaluate(b, b.owner(), "OWNER");
        assertThat(after.path("resolved").path("RECEIVABLES_OVERDUE").asInt()).isEqualTo(1);
        assertThat(after.path("resolved").path("RECEIVABLES_DUE_SOON").asInt()).isEqualTo(1);
        assertThat(alerts(b, "?status=OPEN").path("total").asLong()).isZero();
        put(b, "RECEIVABLES_DUE_SOON", 1);
    }

    private void put(Biz b, String type, int days) throws Exception {
        assertThat(as(b, b.owner(), "OWNER", "PUT", "/finance-alerts/settings/" + type, Map.of("days", days)).status()).isEqualTo(200);
    }

    @Test
    void anIntegrationFailureAlertAppearsAndResolvesAfterALaterSuccess() throws Exception {
        Biz b = biz();
        UUID fail = accounting.startRun(b.space(), "someprovider", "EXPORT", clock);
        accounting.finishRun(b.space(), fail, "FAILED", 0, 2, 3, "AUTH_EXPIRED", "token expired", clock);
        JsonNode e = evaluate(b, b.owner(), "OWNER");
        assertThat(created(e, "INTEGRATION_FAILURE")).isEqualTo(1);
        JsonNode a = alerts(b, "?type=INTEGRATION_FAILURE").path("items").get(0);
        assertThat(a.path("severity").asString()).isEqualTo("CRITICAL");
        assertThat(a.path("details").path("status").asString()).isEqualTo("FAILED");
        assertThat(a.path("details").toString()).doesNotContain("token expired");
        clock.advance(java.time.Duration.ofMinutes(10));
        UUID ok = accounting.startRun(b.space(), "someprovider", "EXPORT", clock);
        accounting.finishRun(b.space(), ok, "SUCCEEDED", 2, 0, 1, null, null, clock);
        assertThat(evaluate(b, b.owner(), "OWNER").path("resolved").path("INTEGRATION_FAILURE").asInt()).isEqualTo(1);
        assertThat(as(b, b.owner(), "OWNER", "GET", "/finance-notifications?category=INTEGRATION", null).body().path("total").asLong()).as("failure, alert and recovery notices").isEqualTo(3);
        Person p = person();
        assertThat(evaluate(p).path("created").has("INTEGRATION_FAILURE")).as("personal workspaces have no accounting integration alert").isFalse();
    }

    // ------------------------------------------------------------------ lifecycle, permissions, isolation, safety

    @Test
    void anAlertCanBeAcknowledgedAndResolvedOnceEachAndEveryActionIsAudited() throws Exception {
        Person p = person();
        String acc = account(p, "Main", "BANK", "INR", "0.00", "2025-01-01");
        ok(tx(p, "EXPENSE", acc, "100.00", "2026-01-10", null));
        ok(tx(p, "EXPENSE", acc, "200.00", "2026-02-10", null));
        evaluate(p);
        String id = alerts(p, "").path("items").get(0).path("id").asString();
        Resp ack = post(p, "/finance-alerts/" + id + "/acknowledge", Map.of());
        assertThat(ack.status()).as(ack.raw().body()).isEqualTo(200);
        assertThat(ack.text("status")).isEqualTo("ACKNOWLEDGED");
        assertThat(ack.text("acknowledged_at")).startsWith("2026-03-15");
        assertThat(post(p, "/finance-alerts/" + id + "/acknowledge", Map.of()).code()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(created(evaluate(p), "SPENDING_CHANGE")).as("acknowledging does not make it fire again").isZero();
        Resp res = post(p, "/finance-alerts/" + id + "/resolve", Map.of("reason", "explained: one-off"));
        assertThat(res.text("status")).isEqualTo("RESOLVED");
        assertThat(res.text("resolved_reason")).isEqualTo("explained: one-off");
        assertThat(post(p, "/finance-alerts/" + id + "/resolve", Map.of()).code()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(post(p, "/finance-alerts/" + id + "/acknowledge", Map.of()).code()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(created(evaluate(p), "SPENDING_CHANGE")).as("a resolved event alert is not re-created").isZero();
        assertThat(get(p, "/finance-alerts/" + id).text("status")).isEqualTo("RESOLVED");
        assertThat(get(p, "/finance-alerts/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(audit(p.space(), "ALERT_ACKNOWLEDGED")).isEqualTo(1);
        assertThat(audit(p.space(), "ALERT_RESOLVED")).isEqualTo(1);
        assertThat(audit(p.space(), "ALERTS_EVALUATED")).isEqualTo(3);
        put(p, "SPENDING_CHANGE", Map.of("enabled", false));
        assertThat(audit(p.space(), "ALERT_SETTING_UPDATED")).isEqualTo(1);
        assertThat(get(p, "/finance-alerts?status=BOGUS").status()).isEqualTo(422);
        assertThat(get(p, "/finance-alerts?type=BOGUS").status()).isEqualTo(422);
        assertThat(get(p, "/finance-alerts?limit=1000").body().path("limit").asInt()).isEqualTo(100);
    }

    @Test
    void settingsAreValidatedPerAlertTypeAndRoleAndAlertsListedPerWorkspace() throws Exception {
        Biz b = biz();
        for (Map<String, Object> bad : List.of(Map.<String, Object>of("threshold", "10"), Map.<String, Object>of("days", 0), Map.<String, Object>of("days", 366), Map.<String, Object>of("currency", "INR"))) {
            assertThat(as(b, b.owner(), "OWNER", "PUT", "/finance-alerts/settings/RECEIVABLES_OVERDUE", bad).status()).as(bad.toString()).isEqualTo(422);
        }
        assertThat(as(b, b.owner(), "OWNER", "PUT", "/finance-alerts/settings/SPENDING_CHANGE", Map.of("threshold", "abc")).status()).isEqualTo(422);
        assertThat(as(b, b.owner(), "OWNER", "PUT", "/finance-alerts/settings/SPENDING_CHANGE", Map.of("threshold", "-5")).status()).isEqualTo(422);
        assertThat(as(b, b.owner(), "OWNER", "PUT", "/finance-alerts/settings/BUDGET_THRESHOLD", Map.of("threshold", "50")).status()).as("a personal-only alert in a business workspace").isEqualTo(404);
        assertThat(as(b, b.owner(), "OWNER", "PUT", "/finance-alerts/settings/NOPE", Map.of("enabled", true)).status()).isEqualTo(404);
        assertThat(as(b, b.owner(), "OWNER", "PUT", "/finance-alerts/settings/SPENDING_CHANGE", Map.of("threshold", "45")).body().path("threshold").asString()).isEqualTo("45");
        Resp settings = bget(b, "/finance-alerts/settings");
        assertThat(settings.body().path("items")).extracting(i -> i.path("alert_type").asString()).doesNotContain("BUDGET_THRESHOLD", "LOW_CASH").contains("RECEIVABLES_OVERDUE", "INTEGRATION_FAILURE");
        for (JsonNode s : settings.body().path("items")) {
            assertThat(s.path("trigger").asString()).isNotBlank();
            assertThat(s.path("severity").asString()).isNotBlank();
        }
        assertThat(settings.body().path("items").toString()).contains("\"is_default\":true");
        UUID user = UUID.randomUUID();
        assertThat(as(b, user, "ACCOUNTANT", "PUT", "/finance-alerts/settings/SPENDING_CHANGE", Map.of("threshold", "10")).status()).as("only settings owners may change thresholds").isEqualTo(403);
        assertThat(as(b, user, "VIEWER", "GET", "/finance-alerts/settings", null).status()).isEqualTo(403);
        assertThat(as(b, user, "ADMIN", "PUT", "/finance-alerts/settings/SPENDING_CHANGE", Map.of("threshold", "60")).status()).isEqualTo(200);
        Biz other = biz();
        assertThat(as(other, other.owner(), "OWNER", "GET", "/finance-alerts/settings", null).body().path("items").toString()).as("another workspace keeps defaults").doesNotContain("\"threshold\":\"60\"");
    }

    @Test
    void permissionsIsolationEntitlementsAndReadOnlyBehaviourAreEnforced() throws Exception {
        Biz a = biz();
        Biz other = biz();
        String c = client(a, "Debtor");
        String inv = ok(draft(a, c, "2026-01-05", "2026-02-05", "INR", List.of(line("Work", "1", "1000.00")), null));
        ok(issue(a, inv, "1000.00"));
        for (String role : List.of("VIEWER", "CONTRIBUTOR", "NOT_A_ROLE")) {
            assertThat(as(a, UUID.randomUUID(), role, "POST", "/finance-alerts/evaluate", Map.of()).status()).as(role).isEqualTo(403);
            assertThat(as(a, UUID.randomUUID(), role, "GET", "/finance-alerts", null).status()).as(role).isEqualTo(403);
        }
        JsonNode admin = evaluate(a, UUID.randomUUID(), "ADMIN");
        assertThat(admin.path("skipped").toString()).as("an Admin's aggregate-only view cannot read the records behind record-level rules").contains("UNUSUAL_EXPENSE").contains("your role cannot read");
        assertThat(created(admin, "RECEIVABLES_OVERDUE")).isEqualTo(1);
        long invoices = count("select count(*) from invoices where tenant_id = :p0", a.space());
        long payments = count("select count(*) from payments where tenant_id = :p0", a.space());
        long expenses = count("select count(*) from business_expenses where tenant_id = :p0", a.space());
        for (int i = 0; i < 3; i++) {
            evaluate(a, a.owner(), "OWNER");
        }
        assertThat(count("select count(*) from invoices where tenant_id = :p0", a.space())).isEqualTo(invoices);
        assertThat(count("select count(*) from payments where tenant_id = :p0", a.space())).isEqualTo(payments);
        assertThat(count("select count(*) from business_expenses where tenant_id = :p0", a.space())).isEqualTo(expenses);
        assertThat(bget(a, "/invoices/" + inv).text("display_status")).as("alerts never change an invoice").isNotEqualTo("PAID");
        assertThat(alerts(other, "").path("total").asLong()).isZero();
        evaluate(other, other.owner(), "OWNER");
        assertThat(alerts(other, "").path("total").asLong()).as("evaluating one workspace creates nothing in another").isZero();
        String id = alerts(a, "").path("items").get(0).path("id").asString();
        assertThat(as(other, other.owner(), "OWNER", "POST", "/finance-alerts/" + id + "/acknowledge", Map.of()).status()).isEqualTo(404);
        assertThat(as(other, other.owner(), "OWNER", "POST", "/finance-alerts/" + id + "/resolve", Map.of()).status()).isEqualTo(404);
        assertThat(raw("GET", "/v1/spaces/" + a.space() + "/finance-alerts", btoken(other, other.owner(), "OWNER"), null).status()).isEqualTo(404);
        assertThat(raw("POST", "/v1/spaces/" + a.space() + "/finance-alerts/evaluate", btoken(other, other.owner(), "OWNER"), Map.of()).status()).isEqualTo(404);
        assertThat(bget(a, "/finance-alerts/" + id).text("status")).isEqualTo("OPEN");
        // entitlement: cancelled workspaces cannot evaluate but can still read what exists
        ok(bpost(a, "/finance-subscription/cancel", Map.of()));
        assertThat(as(a, a.owner(), "OWNER", "POST", "/finance-alerts/evaluate", Map.of()).status()).isEqualTo(403);
        assertThat(alerts(a, "").path("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(as(a, a.owner(), "OWNER", "POST", "/finance-alerts/" + id + "/acknowledge", Map.of()).status()).as("people can still handle existing alerts").isEqualTo(200);
        Person p = person();
        assertThat(raw("POST", base(p) + "/finance-alerts/evaluate", token(p, p.space(), "ADMIN", List.of("workspace:personal")), Map.of()).status()).isEqualTo(403);
    }

    @Test
    void alertsDoNotUseOrNeedAnAiProviderAndDetailsHoldNoSecrets() throws Exception {
        Person p = person();
        String acc = account(p, "Main", "BANK", "INR", "0.00", "2025-01-01");
        ok(tx(p, "EXPENSE", acc, "100.00", "2026-01-10", null));
        ok(tx(p, "EXPENSE", acc, "300.00", "2026-02-10", null));
        evaluate(p);
        assertThat(count("select count(*) from finance_ai_usage where tenant_id = :p0", p.space())).as("no AI request is made for alerts").isZero();
        String all = jdbc.sql("select string_agg(details || title, ' ') from alerts where tenant_id = :t").param("t", p.space()).query(String.class).single();
        assertThat(all).doesNotContainIgnoringCase("token").doesNotContainIgnoringCase("secret").doesNotContainIgnoringCase("password");
    }
}
