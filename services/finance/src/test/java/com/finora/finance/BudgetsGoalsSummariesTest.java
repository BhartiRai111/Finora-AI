package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Budget-versus-actual, savings goals, cash flow and net worth. "Today" is 2026-03-15. */
class BudgetsGoalsSummariesTest extends FinanceTestSupport {

    private JsonNode budgetFor(Person p, String month, String categoryId) throws Exception {
        for (JsonNode b : get(p, "/budgets?month=" + month).body().path("items")) {
            if ((categoryId == null && b.path("category_id").isNull()) || (categoryId != null && categoryId.equals(b.path("category_id").asString()))) {
                return b;
            }
        }
        throw new AssertionError("no budget for " + categoryId);
    }

    private String budget(Person p, String category, String amount) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("amount", amount, "currency", "INR"));
        if (category != null) {
            body.put("category_id", category);
        }
        return ok(post(p, "/budgets", body));
    }

    // ---- budgets ----

    @Test
    void monthBoundariesAreInclusiveAndExactToTheLastDay() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "10000.00");
        String food = category(p, "Food", "EXPENSE");
        budget(p, food, "1000.00");
        ok(tx(p, "EXPENSE", a, "10.00", "2026-02-28", food));
        ok(tx(p, "EXPENSE", a, "100.00", "2026-03-01", food));
        ok(tx(p, "EXPENSE", a, "200.00", "2026-03-31", food));
        ok(tx(p, "EXPENSE", a, "400.00", "2026-04-01", food));
        JsonNode march = budgetFor(p, "2026-03", food);
        assertThat(march.path("spent").asString()).as("Mar 1 and Mar 31 count, Feb 28 and Apr 1 do not").isEqualTo("300.00");
        assertThat(march.path("remaining").asString()).isEqualTo("700.00");
        assertThat(march.path("percent_used").asString()).isEqualTo("30.00");
        assertThat(march.path("status").asString()).isEqualTo("UNDER");
        assertThat(budgetFor(p, "2026-04", food).path("spent").asString()).isEqualTo("400.00");
        assertThat(budgetFor(p, "2026-02", food).path("spent").asString()).isEqualTo("10.00");
        assertThat(get(p, "/budgets?month=2026-3").status()).isEqualTo(422);
        assertThat(get(p, "/budgets?month=nonsense").status()).isEqualTo(422);
    }

    @Test
    void exactlyReachingTheBudgetIsNotOverButOneMinorUnitMoreIs() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "10000.00");
        String food = category(p, "Food", "EXPENSE");
        budget(p, food, "1000.00");
        ok(tx(p, "EXPENSE", a, "799.99", "2026-03-02", food));
        assertThat(budgetFor(p, "2026-03", food).path("status").asString()).isEqualTo("UNDER");
        String one = ok(tx(p, "EXPENSE", a, "0.01", "2026-03-03", food));
        JsonNode eighty = budgetFor(p, "2026-03", food);
        assertThat(eighty.path("spent").asString()).isEqualTo("800.00");
        assertThat(eighty.path("status").asString()).as("exactly 80%").isEqualTo("WARNING");
        ok(tx(p, "EXPENSE", a, "200.00", "2026-03-04", food));
        JsonNode limit = budgetFor(p, "2026-03", food);
        assertThat(limit.path("spent").asString()).isEqualTo("1000.00");
        assertThat(limit.path("remaining").asString()).isEqualTo("0.00");
        assertThat(limit.path("percent_used").asString()).isEqualTo("100.00");
        assertThat(limit.path("status").asString()).as("at the limit is not over").isEqualTo("WARNING");
        ok(tx(p, "EXPENSE", a, "0.01", "2026-03-05", food));
        JsonNode over = budgetFor(p, "2026-03", food);
        assertThat(over.path("status").asString()).isEqualTo("OVER");
        assertThat(over.path("remaining").asString()).isEqualTo("-0.01");
        assertThat(over.path("percent_used").asString()).isEqualTo("100.00");
        post(p, "/transactions/" + one + "/void", Map.of("reason", "undo"));
        assertThat(budgetFor(p, "2026-03", food).path("status").asString()).as("voiding brings it back to the limit").isEqualTo("WARNING");
    }

    @Test
    void onlyPostedExpensesNetOfRefundsCountAndTransfersAdjustmentsAndDraftsDoNot() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "10000.00");
        String b = account(p, "Other", "BANK", "INR", "0", "2026-01-01");
        String food = category(p, "Food", "EXPENSE");
        budget(p, food, "500.00");
        String exp = ok(tx(p, "EXPENSE", a, "200.00", "2026-03-02", food));
        ok(post(p, "/transactions", Map.of("kind", "REFUND", "account_id", a, "amount", "50.00", "occurred_on", "2026-03-03", "refund_of", exp)));
        ok(post(p, "/transfers", Map.of("from_account_id", a, "to_account_id", b, "amount", "300.00", "occurred_on", "2026-03-04")));
        ok(post(p, "/accounts/" + a + "/adjustments", Map.of("new_balance", "1.00", "as_of", "2026-03-05")));
        ok(post(p, "/recurring-rules", Map.of("kind", "EXPENSE", "account_id", a, "category_id", food, "amount", "99.00", "frequency", "DAILY", "start_date", "2026-03-10")));
        post(p, "/recurring-rules/run", Map.of());
        assertThat(get(p, "/transactions?status=DRAFT").body().path("items").size()).isGreaterThan(0);
        JsonNode item = budgetFor(p, "2026-03", food);
        assertThat(item.path("spent").asString()).as("200 spent less 50 refunded; nothing else counts").isEqualTo("150.00");
        assertThat(item.path("remaining").asString()).isEqualTo("350.00");
    }

    @Test
    void parentBudgetsIncludeSubcategoriesOverallBudgetsIncludeEverythingAndCurrenciesStaySeparate() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "10000.00");
        String usd = account(p, "Dollar", "BANK", "USD", "1000.00", "2026-01-01");
        String food = category(p, "Food", "EXPENSE");
        Resp child = raw("POST", base(p) + "/categories", token(p), Map.of("name", "Veg", "kind", "EXPENSE", "parent_id", food));
        String other = category(p, "Other", "EXPENSE");
        budget(p, food, "1000.00");
        budget(p, null, "5000.00");
        ok(post(p, "/budgets", Map.of("amount", "100.00", "currency", "USD")));
        ok(tx(p, "EXPENSE", a, "100.00", "2026-03-02", food));
        ok(tx(p, "EXPENSE", a, "50.00", "2026-03-02", child.id()));
        ok(tx(p, "EXPENSE", a, "25.00", "2026-03-02", other));
        ok(tx(p, "EXPENSE", a, "5.00", "2026-03-02", null));
        ok(tx(p, "EXPENSE", usd, "30.00", "2026-03-02", food));
        assertThat(budgetFor(p, "2026-03", food).path("spent").asString()).as("parent includes the child, not the USD spend").isEqualTo("150.00");
        JsonNode overall = null;
        JsonNode overallUsd = null;
        for (JsonNode b : get(p, "/budgets?month=2026-03").body().path("items")) {
            if (b.path("category_id").isNull() && b.path("currency").asString().equals("INR")) {
                overall = b;
            }
            if (b.path("category_id").isNull() && b.path("currency").asString().equals("USD")) {
                overallUsd = b;
            }
        }
        assertThat(overall.path("spent").asString()).isEqualTo("180.00");
        assertThat(overallUsd.path("spent").asString()).isEqualTo("30.00");
    }

    @Test
    void budgetRulesUniquenessVersionsArchiveAndValidation() throws Exception {
        Person p = person();
        String food = category(p, "Food", "EXPENSE");
        String income = category(p, "Pay", "INCOME");
        String id = budget(p, food, "100.00");
        assertThat(post(p, "/budgets", Map.of("category_id", food, "amount", "5.00", "currency", "INR")).status()).as("one live budget per category and currency").isEqualTo(409);
        assertThat(post(p, "/budgets", Map.of("category_id", income, "amount", "5.00", "currency", "INR")).status()).as("income categories have no budget").isEqualTo(422);
        assertThat(post(p, "/budgets", Map.of("category_id", food, "amount", "0", "currency", "INR")).status()).isEqualTo(422);
        assertThat(post(p, "/budgets", Map.of("category_id", food, "amount", "1.5", "currency", "JPY")).status()).isEqualTo(422);
        assertThat(patch(p, "/budgets/" + id, Map.of("amount", "150.00", "version", 0)).text("amount")).isEqualTo("150.00");
        assertThat(patch(p, "/budgets/" + id, Map.of("amount", "200.00", "version", 0)).status()).isEqualTo(409);
        assertThat(post(p, "/budgets/" + id + "/archive", Map.of()).status()).isEqualTo(204);
        assertThat(get(p, "/budgets").body().path("items")).isEmpty();
        assertThat(budget(p, food, "75.00")).as("a new budget can replace an archived one").isNotNull();
    }

    @Test
    void theDefaultMonthIsTheUsersLocalMonth() throws Exception {
        Person p = person();
        clock.set("2026-03-31T19:00:00Z");
        assertThat(get(p, "/budgets").text("month")).isEqualTo("2026-03");
        put(p, "/finance-settings", Map.of("timezone", "Asia/Kolkata"));
        assertThat(get(p, "/budgets").text("month")).as("already April 1 in India").isEqualTo("2026-04");
    }

    // ---- goals ----

    @Test
    void goalsTrackExactProgressAndTheRequiredMonthlySavingRoundsUp() throws Exception {
        Person p = person();
        Resp g = post(p, "/goals", Map.of("name", "Emergency fund", "target_amount", "1000.00", "currency", "INR", "target_date", "2026-06-15"));
        assertThat(g.status()).isEqualTo(201);
        assertThat(g.text("status")).isEqualTo("IN_PROGRESS");
        assertThat(g.body().path("months_left").asInt()).isEqualTo(3);
        assertThat(g.text("required_monthly")).as("1000 / 3 = 333.33.., rounded up so the goal is always reached").isEqualTo("333.34");
        Resp c1 = post(p, "/goals/" + g.id() + "/contributions", Map.of("amount", "100.00", "contributed_on", "2026-03-10", "note", "first"));
        assertThat(c1.status()).isEqualTo(201);
        assertThat(c1.text("saved")).isEqualTo("100.00");
        assertThat(c1.text("remaining")).isEqualTo("900.00");
        assertThat(c1.text("percent")).isEqualTo("10.00");
        assertThat(c1.text("required_monthly")).isEqualTo("300.00");
        Resp third = post(p, "/goals/" + g.id() + "/contributions", Map.of("amount", "0.01"));
        assertThat(third.text("saved")).isEqualTo("100.01");
        assertThat(third.text("percent")).isEqualTo("10.00");
        assertThat(post(p, "/goals/" + g.id() + "/contributions", Map.of("amount", "-200.00")).status()).as("cannot withdraw more than saved").isEqualTo(422);
        assertThat(post(p, "/goals/" + g.id() + "/contributions", Map.of("amount", "-0.01")).text("saved")).isEqualTo("100.00");
        assertThat(post(p, "/goals/" + g.id() + "/contributions", Map.of("amount", "0")).status()).isEqualTo(422);
        assertThat(post(p, "/goals/" + g.id() + "/contributions", Map.of("amount", "5.00", "contributed_on", "2026-03-16")).status()).as("future-dated").isEqualTo(422);
        Resp done = post(p, "/goals/" + g.id() + "/contributions", Map.of("amount", "900.00"));
        assertThat(done.text("status")).isEqualTo("ACHIEVED");
        assertThat(done.text("remaining")).isEqualTo("0.00");
        assertThat(done.body().path("required_monthly").isNull()).isTrue();
        assertThat(get(p, "/goals/" + g.id()).body().path("contributions")).as("four accepted, the rejected ones left no trace").hasSize(4);
    }

    @Test
    void overdueGoalsDemandTheRemainderNowAndInputIsValidated() throws Exception {
        Person p = person();
        String id = ok(post(p, "/goals", Map.of("name", "Trip", "target_amount", "250.50", "currency", "INR", "target_date", "2026-03-20")));
        clock.set("2026-04-01T10:00:00Z");
        JsonNode g = get(p, "/goals/" + id).body();
        assertThat(g.path("months_left").asInt()).isZero();
        assertThat(g.path("required_monthly").asString()).isEqualTo("250.50");
        assertThat(post(p, "/goals", Map.of("name", "Past", "target_amount", "1.00", "currency", "INR", "target_date", "2026-03-31")).status()).isEqualTo(422);
        assertThat(post(p, "/goals", Map.of("name", "", "target_amount", "1.00", "currency", "INR")).status()).isEqualTo(422);
        assertThat(post(p, "/goals", Map.of("name", "Yen", "target_amount", "1000.5", "currency", "JPY")).status()).isEqualTo(422);
        assertThat(post(p, "/goals", Map.of("name", "Yen", "target_amount", "1000", "currency", "JPY")).status()).isEqualTo(201);
        assertThat(patch(p, "/goals/" + id, Map.of("target_amount", "300.00", "clear_target_date", true, "version", 0)).body().path("required_monthly").isNull()).isTrue();
        assertThat(patch(p, "/goals/" + id, Map.of("name", "x", "version", 0)).status()).isEqualTo(409);
        assertThat(post(p, "/goals/" + id + "/archive", Map.of()).text("status")).isEqualTo("ARCHIVED");
        assertThat(post(p, "/goals/" + id + "/contributions", Map.of("amount", "1.00")).status()).isEqualTo(409);
        assertThat(get(p, "/goals").body().path("items").size()).isEqualTo(1);
        assertThat(get(p, "/goals?include_archived=true").body().path("items").size()).isEqualTo(2);
    }

    @Test
    void goalContributionsAreIdempotentAndRequireAKey() throws Exception {
        Person p = person();
        String id = ok(post(p, "/goals", Map.of("name", "Car", "target_amount", "500.00", "currency", "INR")));
        Resp one = postKeyed(p, "/goals/" + id + "/contributions", Map.of("amount", "25.00"), "c-1");
        Resp two = postKeyed(p, "/goals/" + id + "/contributions", Map.of("amount", "25.00"), "c-1");
        assertThat(one.text("saved")).isEqualTo("25.00");
        assertThat(two.text("saved")).isEqualTo("25.00");
        assertThat(two.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(postNoKey(p, "/goals/" + id + "/contributions", Map.of("amount", "25.00")).status()).isEqualTo(400);
    }

    // ---- cash flow and net worth ----

    @Test
    void cashFlowIsReportedPerCurrencyWithCategoryBreakdownAndExactTotals() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "0");
        String usd = account(p, "Dollar", "BANK", "USD", "0", "2026-01-01");
        String salary = category(p, "Salary", "INCOME");
        String food = category(p, "Food", "EXPENSE");
        ok(tx(p, "INCOME", a, "50000.00", "2026-03-01", salary));
        ok(tx(p, "EXPENSE", a, "0.10", "2026-03-02", food));
        ok(tx(p, "EXPENSE", a, "0.20", "2026-03-03", food));
        ok(tx(p, "EXPENSE", a, "10.00", "2026-03-04", null));
        ok(tx(p, "INCOME", usd, "100.00", "2026-03-05", null));
        ok(tx(p, "EXPENSE", a, "999.00", "2026-02-28", food));
        JsonNode currencies = get(p, "/summaries/cashflow?from=2026-03-01&to=2026-03-31").body().path("currencies");
        assertThat(currencies).hasSize(2);
        JsonNode inr = currencies.get(0);
        assertThat(inr.path("currency").asString()).isEqualTo("INR");
        assertThat(inr.path("income").asString()).isEqualTo("50000.00");
        assertThat(inr.path("gross_expense").asString()).isEqualTo("10.30");
        assertThat(inr.path("net").asString()).isEqualTo("49989.70");
        boolean sawFood = false;
        boolean sawUncategorised = false;
        for (JsonNode c : inr.path("categories")) {
            if ("Food".equals(c.path("name").asString())) {
                assertThat(c.path("expense").asString()).isEqualTo("0.30");
                sawFood = true;
            }
            if (c.path("category_id").isNull()) {
                assertThat(c.path("expense").asString()).isEqualTo("10.00");
                sawUncategorised = true;
            }
        }
        assertThat(sawFood && sawUncategorised).isTrue();
        assertThat(currencies.get(1).path("currency").asString()).isEqualTo("USD");
        assertThat(currencies.get(1).path("income").asString()).isEqualTo("100.00");
        assertThat(get(p, "/summaries/cashflow").body().path("from").asString()).as("defaults to the current month").isEqualTo("2026-03-01");
        assertThat(get(p, "/summaries/cashflow?from=2026-04-01&to=2026-03-01").status()).isEqualTo(422);
        assertThat(get(p, "/summaries/cashflow?from=2025-01-01&to=2025-01-31").body().path("currencies")).as("an empty period is an empty list").isEmpty();
    }

    @Test
    void netWorthIsAssetsMinusLiabilitiesPerCurrencyWithComponents() throws Exception {
        Person p = person();
        bank(p, "Main", "1000.00");
        account(p, "Cash", "CASH", "INR", "200.50", "2026-01-01");
        account(p, "Card", "CREDIT_CARD", "INR", "300.00", "2026-01-01");
        account(p, "Home loan", "LOAN", "INR", "5000.00", "2026-01-01");
        account(p, "Flat", "OTHER_ASSET", "INR", "9000.00", "2026-01-01");
        account(p, "Dollar", "BANK", "USD", "50.00", "2026-01-01");
        String empty = bank(p, "Old", "0");
        post(p, "/accounts/" + empty + "/archive", Map.of());
        JsonNode currencies = get(p, "/net-worth").body().path("currencies");
        assertThat(currencies).hasSize(2);
        JsonNode inr = currencies.get(0);
        assertThat(inr.path("assets").asString()).isEqualTo("10200.50");
        assertThat(inr.path("liabilities").asString()).isEqualTo("5300.00");
        assertThat(inr.path("net_worth").asString()).isEqualTo("4900.50");
        assertThat(inr.path("components")).as("an archived empty account is not a component").hasSize(5);
        assertThat(currencies.get(1).path("net_worth").asString()).isEqualTo("50.00");
    }

    @Test
    void theNetWorthSeriesHasOnePointPerMonthEndAndIsCapped() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "1000.00");
        ok(tx(p, "EXPENSE", a, "100.00", "2026-02-10", null));
        ok(tx(p, "INCOME", a, "50.00", "2026-03-10", null));
        JsonNode points = get(p, "/net-worth/series?from=2026-01-05&to=2026-03-15").body().path("points");
        assertThat(points).hasSize(3);
        assertThat(points.get(0).path("date").asString()).isEqualTo("2026-01-31");
        assertThat(points.get(0).path("currencies").get(0).path("net_worth").asString()).isEqualTo("1000.00");
        assertThat(points.get(1).path("date").asString()).isEqualTo("2026-02-28");
        assertThat(points.get(1).path("currencies").get(0).path("net_worth").asString()).isEqualTo("900.00");
        assertThat(points.get(2).path("date").asString()).as("the current month ends today").isEqualTo("2026-03-15");
        assertThat(points.get(2).path("currencies").get(0).path("net_worth").asString()).isEqualTo("950.00");
        assertThat(get(p, "/net-worth/series?from=2020-01-01&to=2026-03-15").status()).isEqualTo(422);
        assertThat(get(p, "/net-worth/series?from=2026-03-01&to=2026-12-31").status()).isEqualTo(422);
        assertThat(get(p, "/net-worth/series").body().path("points")).hasSize(12);
        assertThat(UUID.randomUUID()).isNotNull();
    }
}
