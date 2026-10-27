package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Reports for personal and business workspaces: definitions, exclusions, currencies, periods, comparison, CSV export. */
class ReportsTest extends BusinessTestSupport {

    private JsonNode cur(Resp r, String currency) {
        for (JsonNode c : r.body().path("currencies")) {
            if (currency.equals(c.path("currency").asString())) {
                return c;
            }
        }
        throw new AssertionError("no " + currency + " in " + r.raw().body());
    }

    private JsonNode row(JsonNode currency, String period) {
        for (JsonNode r : currency.path("rows")) {
            if (period.equals(r.path("period").asString())) {
                return r;
            }
        }
        throw new AssertionError("no period " + period);
    }

    private String v(JsonNode row, String col) {
        return row.path("values").path(col).asString();
    }

    private record World(Person p, String main, String savings, String usd) {
    }

    private World personalWorld() throws Exception {
        Person p = person();
        String main = bank(p, "Main", "0.00");
        String savings = bank(p, "Savings", "0.00");
        String usd = account(p, "Dollar", "BANK", "USD", "0.00", "2026-01-01");
        ok(tx(p, "INCOME", main, "1000.10", "2026-01-15", null));
        ok(tx(p, "INCOME", main, "0.10", "2026-02-10", null));
        ok(tx(p, "INCOME", main, "0.20", "2026-02-20", null));
        ok(tx(p, "EXPENSE", main, "50.55", "2026-02-25", null));
        ok(tx(p, "INCOME", main, "2000.00", "2026-03-04", null));
        String e1 = ok(tx(p, "EXPENSE", main, "100.00", "2026-03-05", null));
        ok(post(p, "/transactions", Map.of("kind", "REFUND", "account_id", main, "amount", "30.00", "occurred_on", "2026-03-06", "refund_of", e1)));
        ok(post(p, "/transfers", Map.of("from_account_id", main, "to_account_id", savings, "amount", "500.00", "occurred_on", "2026-03-07")));
        String gone = ok(tx(p, "EXPENSE", main, "999.00", "2026-03-08", null));
        ok(post(p, "/transactions/" + gone + "/void", Map.of("reason", "mistake")));
        ok(tx(p, "EXPENSE", usd, "5.00", "2026-02-16", null));
        return new World(p, main, savings, usd);
    }

    @Test
    void personalCashFlowIsExactPerMonthAndExcludesTransfersVoidedAndEverythingElse() throws Exception {
        World w = personalWorld();
        Resp r = get(w.p(), "/finance-reports/cash-flow?from=2026-01-01&to=2026-03-15");
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.text("report")).isEqualTo("cash-flow");
        assertThat(r.text("granularity")).isEqualTo("MONTH");
        assertThat(r.body().path("definitions")).isNotEmpty();
        assertThat(r.body().path("currencies")).hasSize(2);
        assertThat(r.body().path("currencies").get(0).path("currency").asString()).isEqualTo("INR");
        JsonNode inr = cur(r, "INR");
        assertThat(v(row(inr, "2026-01"), "income")).isEqualTo("1000.10");
        JsonNode feb = row(inr, "2026-02");
        assertThat(v(feb, "income")).as("0.10 + 0.20 is exactly 0.30").isEqualTo("0.30");
        assertThat(v(feb, "expense")).isEqualTo("50.55");
        assertThat(v(feb, "net")).isEqualTo("-50.25");
        JsonNode mar = row(inr, "2026-03");
        assertThat(v(mar, "income")).isEqualTo("2000.00");
        assertThat(v(mar, "expense")).as("the voided 999.00 and the transfer are excluded").isEqualTo("100.00");
        assertThat(v(mar, "refunds")).isEqualTo("30.00");
        assertThat(v(mar, "net_expense")).isEqualTo("70.00");
        assertThat(v(mar, "net")).isEqualTo("1930.00");
        assertThat(inr.path("totals").path("income").asString()).isEqualTo("3000.40");
        assertThat(inr.path("totals").path("net_expense").asString()).isEqualTo("120.55");
        assertThat(inr.path("totals").path("net").asString()).isEqualTo("2879.85");
        // USD stands alone: nothing is converted or added to INR, and months without activity are flagged as such
        JsonNode usd = cur(r, "USD");
        assertThat(v(row(usd, "2026-02"), "expense")).isEqualTo("5.00");
        assertThat(row(usd, "2026-02").path("activity").asBoolean()).isTrue();
        assertThat(row(usd, "2026-01").path("activity").asBoolean()).isFalse();
        assertThat(usd.path("totals").path("expense").asString()).isEqualTo("5.00");

        // the same numbers as the existing summary endpoint
        JsonNode summary = get(w.p(), "/summaries/cashflow?from=2026-03-01&to=2026-03-15").body().path("currencies").get(0);
        assertThat(summary.path("income").asString()).isEqualTo(v(mar, "income"));
        assertThat(summary.path("net_expense").asString()).isEqualTo(v(mar, "net_expense"));
        assertThat(summary.path("net").asString()).isEqualTo(v(mar, "net"));
    }

    @Test
    void datesAreInclusiveWeeksStartOnMondayAndTheWorkspaceTimeZoneDecidesToday() throws Exception {
        Person p = person();
        String main = bank(p, "Main", "0.00");
        ok(tx(p, "EXPENSE", main, "1.00", "2026-03-01", null));
        ok(tx(p, "EXPENSE", main, "2.00", "2026-03-31", null));
        ok(tx(p, "EXPENSE", main, "4.00", "2026-04-01", null));
        clock.set("2026-04-05T10:00:00Z");
        JsonNode march = cur(get(p, "/finance-reports/cash-flow?from=2026-03-01&to=2026-03-31"), "INR");
        assertThat(march.path("totals").path("expense").asString()).as("both end dates are included").isEqualTo("3.00");
        assertThat(cur(get(p, "/finance-reports/cash-flow?from=2026-03-31&to=2026-04-01"), "INR").path("totals").path("expense").asString()).isEqualTo("6.00");

        clock.set("2026-03-15T10:00:00Z");
        Person q = person();
        String acc = bank(q, "Main", "0.00");
        ok(tx(q, "EXPENSE", acc, "3.00", "2026-03-05", null)); // Thursday, ISO week 10
        ok(tx(q, "EXPENSE", acc, "5.00", "2026-03-10", null)); // Tuesday, ISO week 11
        JsonNode weekly = cur(get(q, "/finance-reports/cash-flow?from=2026-03-02&to=2026-03-15&granularity=week"), "INR");
        assertThat(weekly.path("rows")).hasSize(2);
        assertThat(v(row(weekly, "2026-W10"), "expense")).isEqualTo("3.00");
        assertThat(v(row(weekly, "2026-W11"), "expense")).isEqualTo("5.00");
        assertThat(row(weekly, "2026-W10").path("start").asString()).isEqualTo("2026-03-02");
        assertThat(row(weekly, "2026-W11").path("end").asString()).isEqualTo("2026-03-15");

        // "today" in Kolkata is already the 16th when it is 20:00 UTC on the 15th, so a transaction dated the 16th is allowed and in the default range
        assertThat(put(q, "/finance-settings", Map.of("timezone", "Asia/Kolkata")).status()).isEqualTo(200);
        clock.set("2026-03-15T20:00:00Z");
        ok(tx(q, "EXPENSE", acc, "7.00", "2026-03-16", null));
        Resp def = get(q, "/finance-reports/cash-flow");
        assertThat(def.text("to")).isEqualTo("2026-03-16");
        assertThat(v(row(cur(def, "INR"), "2026-03"), "expense")).isEqualTo("15.00");
    }

    @Test
    void comparisonUsesTheSameLengthPeriodBeforeOrAnyPeriodAndLeavesAnUndefinedPercentBlank() throws Exception {
        World w = personalWorld();
        Resp r = get(w.p(), "/finance-reports/cash-flow?from=2026-03-01&to=2026-03-15&compare=previous");
        JsonNode c = r.body().path("comparison");
        assertThat(c.path("from").asString()).isEqualTo("2026-02-14");
        assertThat(c.path("to").asString()).isEqualTo("2026-02-28");
        JsonNode inr = null;
        for (JsonNode x : c.path("currencies")) {
            if ("INR".equals(x.path("currency").asString())) {
                inr = x;
            }
        }
        assertThat(inr).isNotNull();
        assertThat(inr.path("current").path("income").asString()).isEqualTo("2000.00");
        assertThat(inr.path("previous").path("income").asString()).isEqualTo("0.20");
        assertThat(inr.path("change").path("income").path("amount").asString()).isEqualTo("1999.80");
        assertThat(inr.path("change").path("income").path("percent").asString()).isEqualTo("999900.00");
        assertThat(inr.path("previous").path("refunds").asString()).isEqualTo("0.00");
        assertThat(inr.path("change").path("refunds").path("percent").isNull()).as("no percent when the earlier value is zero").isTrue();
        assertThat(inr.path("change").path("expense").path("amount").asString()).isEqualTo("49.45");
        // a currency that only existed in the earlier period still appears, with a negative change
        JsonNode usd = null;
        for (JsonNode x : c.path("currencies")) {
            if ("USD".equals(x.path("currency").asString())) {
                usd = x;
            }
        }
        assertThat(usd).isNotNull();
        assertThat(usd.path("current").path("expense").asString()).isEqualTo("0.00");
        assertThat(usd.path("change").path("expense").path("amount").asString()).isEqualTo("-5.00");
        assertThat(usd.path("change").path("expense").path("percent").asString()).isEqualTo("-100.00");
        JsonNode custom = get(w.p(), "/finance-reports/cash-flow?from=2026-03-01&to=2026-03-15&compare_from=2026-01-01&compare_to=2026-01-31").body().path("comparison");
        assertThat(custom.path("from").asString()).isEqualTo("2026-01-01");
        assertThat(custom.path("currencies").get(0).path("previous").path("income").asString()).isEqualTo("1000.10");
    }

    @Test
    void emptyWorkspacesAndBadParametersGiveValidEmptyReportsOrClearErrors() throws Exception {
        Person p = person();
        Resp empty = get(p, "/finance-reports/cash-flow");
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.body().path("currencies")).as("no fabricated zero rows").isEmpty();
        assertThat(get(p, "/finance-reports/net-worth").body().path("currencies")).isEmpty();
        assertThat(get(p, "/finance-reports/budget-vs-actual").body().path("currencies")).isEmpty();
        assertThat(get(p, "/finance-reports/cash-flow?granularity=day").status()).isEqualTo(422);
        assertThat(get(p, "/finance-reports/cash-flow?from=2026-03-10&to=2026-03-01").status()).isEqualTo(422);
        assertThat(get(p, "/finance-reports/cash-flow?from=nope").status()).isEqualTo(422);
        assertThat(get(p, "/finance-reports/cash-flow?from=2010-01-01&to=2026-03-01&granularity=week").status()).as("too many periods").isEqualTo(422);
        assertThat(get(p, "/finance-reports/cash-flow?from=2000-01-01&to=2026-03-01").status()).as("more than ten years").isEqualTo(422);
        assertThat(get(p, "/finance-reports/cash-flow?compare_from=2026-01-01").status()).as("both compare dates are required").isEqualTo(422);
        assertThat(get(p, "/finance-reports/budget-vs-actual?compare=previous").status()).isEqualTo(422);
        assertThat(get(p, "/finance-reports/receivables").status()).as("a business report in a personal profile").isEqualTo(404);
        assertThat(get(p, "/finance-reports/nope").status()).isEqualTo(404);
        Resp list = get(p, "/finance-reports");
        assertThat(list.body().path("reports")).hasSize(3);
        Biz b = biz();
        assertThat(bget(b, "/finance-reports/business-performance").body().path("currencies")).isEmpty();
        assertThat(bget(b, "/finance-reports/receivables").body().path("currencies")).isEmpty();
        assertThat(bget(b, "/finance-reports/project-profitability").body().path("currencies")).isEmpty();
        assertThat(bget(b, "/finance-reports/cash-flow").status()).as("a personal report in a business workspace").isEqualTo(404);
    }

    @Test
    void budgetAndNetWorthReportsMatchTheExistingEndpoints() throws Exception {
        World w = personalWorld();
        String food = category(w.p(), "Food", "EXPENSE");
        ok(post(w.p(), "/budgets", Map.of("category_id", food, "amount", "200.00", "currency", "INR")));
        ok(tx(w.p(), "EXPENSE", w.main(), "160.00", "2026-03-09", food));
        Resp r = get(w.p(), "/finance-reports/budget-vs-actual?month=2026-03");
        JsonNode row = cur(r, "INR").path("rows").get(0);
        assertThat(row.path("period").asString()).isEqualTo("Food");
        assertThat(v(row, "spent")).isEqualTo("160.00");
        assertThat(v(row, "percent_used")).isEqualTo("80.00");
        assertThat(v(row, "status")).isEqualTo("WARNING");
        assertThat(get(w.p(), "/budgets?month=2026-03").body().path("items").get(0).path("spent").asString()).isEqualTo(v(row, "spent"));

        Resp nw = get(w.p(), "/finance-reports/net-worth?from=2026-01-01&to=2026-03-15");
        JsonNode last = null;
        for (JsonNode x : cur(nw, "INR").path("rows")) {
            last = x;
        }
        assertThat(last).isNotNull();
        assertThat(last.path("period").asString()).isEqualTo("2026-03");
        assertThat(v(last, "net_worth")).isEqualTo(get(w.p(), "/net-worth").body().path("currencies").get(0).path("net_worth").asString());
    }

    // ------------------------------------------------------------------ business

    private record BizWorld(Biz b, String client, String project) {
    }

    private BizWorld businessWorld() throws Exception {
        Biz b = biz();
        String client = client(b, "Acme");
        String project = project(b, "Warehouse", client, "INR", "1000.00");
        String cat = ok(bpost(b, "/expense-categories", Map.of("name", "Materials")));
        String i1 = ok(draft(b, client, "2026-03-02", null, "INR", List.of(line("Work", "1", "1000.00", "0", "18")), project));
        ok(issue(b, i1, "1180.00"));
        String i2 = ok(draft(b, client, "2026-03-05", "2026-03-10", "INR", List.of(line("Work", "1", "200.00")), null));
        ok(issue(b, i2, "200.00"));
        draftInvoice(b, client, "999.00");
        String gone = ok(draft(b, client, "2026-03-06", null, "INR", List.of(line("Work", "1", "300.00")), null));
        ok(issue(b, gone, "300.00"));
        ok(bpost(b, "/invoices/" + gone + "/cancel", Map.of("confirm", true, "reason", "error")));
        String i5 = ok(draft(b, client, "2026-02-10", "2026-02-20", "INR", List.of(line("Work", "1", "100.00")), null));
        ok(issue(b, i5, "100.00"));
        ok(pay(b, client, "500.00", "P1", List.of(alloc(i1, "500.00"))));
        String voided = ok(pay(b, client, "77.00", "P2", null));
        ok(bpost(b, "/payments/" + voided + "/void", Map.of("reason", "bounced")));
        ok(pay(b, client, "25.00", "P3", null));
        ok(expense(b, "300.00", "2026-03-03", null, project, cat));
        ok(expense(b, "50.00", "2026-03-04", null, null, null));
        String gone2 = ok(expense(b, "400.00", "2026-03-05", null, null, null));
        ok(bpost(b, "/expenses/" + gone2 + "/void", Map.of("reason", "dup")));
        ok(expense(b, "10.00", "2026-02-20", null, null, null));
        return new BizWorld(b, client, project);
    }

    @Test
    void businessPerformanceSeparatesInvoicedFromReceivedAndMatchesTheBusinessSummary() throws Exception {
        BizWorld w = businessWorld();
        Resp r = bget(w.b(), "/finance-reports/business-performance?from=2026-02-01&to=2026-03-15");
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        JsonNode inr = cur(r, "INR");
        JsonNode feb = row(inr, "2026-02");
        assertThat(v(feb, "invoiced_net")).isEqualTo("100.00");
        assertThat(v(feb, "received")).isEqualTo("0.00");
        assertThat(v(feb, "expenses")).isEqualTo("10.00");
        assertThat(v(feb, "operating_result")).isEqualTo("90.00");
        assertThat(v(feb, "net_cash")).isEqualTo("-10.00");
        JsonNode mar = row(inr, "2026-03");
        assertThat(v(mar, "invoiced_net")).as("drafts and the cancelled invoice never count; tax is excluded").isEqualTo("1200.00");
        assertThat(v(mar, "received")).as("partial and unallocated receipts count, the voided one does not").isEqualTo("525.00");
        assertThat(v(mar, "expenses")).isEqualTo("350.00");
        assertThat(v(mar, "operating_result")).isEqualTo("850.00");
        assertThat(v(mar, "net_cash")).isEqualTo("175.00");
        assertThat(inr.path("totals").path("operating_result").asString()).isEqualTo("940.00");
        JsonNode overview = null;
        for (JsonNode c : bget(w.b(), "/business-summaries/overview?from=2026-03-01&to=2026-03-15").body().path("currencies")) {
            overview = c;
        }
        assertThat(overview).isNotNull();
        assertThat(overview.path("invoiced_net").asString()).isEqualTo(v(mar, "invoiced_net"));
        assertThat(overview.path("received").asString()).isEqualTo(v(mar, "received"));
        assertThat(overview.path("expenses").asString()).isEqualTo(v(mar, "expenses"));
        assertThat(overview.path("operating_result").asString()).isEqualTo(v(mar, "operating_result"));
        assertThat(r.body().path("notes").toString()).contains("accrual").contains("nothing is converted");
        // comparing March with February
        JsonNode cmp = bget(w.b(), "/finance-reports/business-performance?from=2026-03-01&to=2026-03-31&compare_from=2026-02-01&compare_to=2026-02-28").body().path("comparison").path("currencies").get(0);
        assertThat(cmp.path("change").path("invoiced_net").path("amount").asString()).isEqualTo("1100.00");
        assertThat(cmp.path("change").path("invoiced_net").path("percent").asString()).isEqualTo("1100.00");
        assertThat(cmp.path("change").path("received").path("percent").isNull()).isTrue();
    }

    @Test
    void receivablesAgingAndByClientFollowTheBusinessDefinitions() throws Exception {
        BizWorld w = businessWorld();
        JsonNode a = cur(bget(w.b(), "/finance-reports/receivables"), "INR");
        assertThat(a.path("totals").path("outstanding").asString()).isEqualTo("980.00");
        assertThat(a.path("totals").path("overdue").asString()).isEqualTo("300.00");
        assertThat(a.path("totals").path("invoices").asString()).isEqualTo("3");
        List<String> aging = new ArrayList<>();
        a.path("rows").forEach(x -> aging.add(x.path("period").asString() + "=" + v(x, "amount")));
        assertThat(aging).containsExactly("Not yet due=680.00", "1-30 days overdue=300.00", "31-60 days overdue=0.00", "61-90 days overdue=0.00", "Over 90 days overdue=0.00");
        JsonNode byClient = cur(bget(w.b(), "/finance-reports/receivables-by-client"), "INR");
        assertThat(byClient.path("rows")).hasSize(1);
        assertThat(byClient.path("rows").get(0).path("period").asString()).isEqualTo("Acme");
        assertThat(v(byClient.path("rows").get(0), "outstanding")).isEqualTo("980.00");
        assertThat(v(byClient.path("rows").get(0), "overdue")).isEqualTo("300.00");
        // an earlier day: the payment dated 03-10 had not been received on 03-08
        JsonNode earlier = cur(bget(w.b(), "/finance-reports/receivables?as_of=2026-03-08"), "INR");
        assertThat(earlier.path("totals").path("outstanding").asString()).isEqualTo("1480.00");
        assertThat(bget(w.b(), "/finance-reports/receivables?as_of=2026-03-16").status()).as("not in the future").isEqualTo(422);
        // a partial payment reduces the outstanding amount exactly
        assertThat(bget(w.b(), "/finance-reports/receivables?compare=previous").status()).isEqualTo(422);
    }

    @Test
    void projectProfitabilityReportMatchesTheProjectEndpoints() throws Exception {
        BizWorld w = businessWorld();
        JsonNode inr = cur(bget(w.b(), "/finance-reports/project-profitability?from=2026-03-01&to=2026-03-31"), "INR");
        JsonNode row = inr.path("rows").get(0);
        assertThat(row.path("period").asString()).isEqualTo("Warehouse");
        assertThat(v(row, "revenue_net")).isEqualTo("1000.00");
        assertThat(v(row, "costs")).isEqualTo("300.00");
        assertThat(v(row, "profit")).isEqualTo("700.00");
        assertThat(v(row, "margin_percent")).isEqualTo("70.00");
        assertThat(v(row, "budget_used_percent")).isEqualTo("30.00");
        assertThat(bget(w.b(), "/business-summaries/projects/" + w.project() + "?from=2026-03-01&to=2026-03-31").text("profit")).isEqualTo(v(row, "profit"));
    }

    // ------------------------------------------------------------------ export

    @Test
    void csvExportMatchesTheReportAndNeutralisesFormulas() throws Exception {
        Biz b = biz();
        String evil = client(b, "=HYPERLINK(\"http://evil.example\",\"click\")");
        String plus = client(b, "+cmd|' /C calc'!A0");
        String atSign = client(b, "@SUM(1+1)");
        for (String c : List.of(evil, plus, atSign)) {
            String i = ok(draft(b, c, "2026-03-02", null, "INR", List.of(line("Work", "1", "10.00")), null));
            ok(issue(b, i, "10.00"));
        }
        BinResp csv = bytes("GET", "/v1/spaces/" + b.space() + "/finance-reports/receivables-by-client/export", btoken(b, b.owner(), "OWNER"), null, null);
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.header("Content-Type")).startsWith("text/csv");
        assertThat(csv.header("Content-Disposition")).isEqualTo("attachment; filename=\"receivables-by-client--" + "2026-03-15.csv\"");
        assertThat(csv.header("X-Content-Type-Options")).isEqualTo("nosniff");
        String body = csv.text();
        assertThat(body).startsWith("currency,period,start,end,activity,outstanding,overdue,invoices\r\n");
        for (String line : body.split("\r\n")) {
            for (String cell : line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                String c = cell.startsWith("\"") ? cell.substring(1) : cell;
                assertThat(c).as("no cell may start like a formula: " + line).doesNotStartWith("=").doesNotStartWith("@").doesNotStartWith("+");
            }
        }
        assertThat(body).contains("'=HYPERLINK").contains("'+cmd").contains("'@SUM");
        assertThat(body).contains("INR,TOTAL");
        // numbers are written as plain decimals, including negative ones
        Person p = person();
        String acc = bank(p, "Main", "0.00");
        ok(tx(p, "EXPENSE", acc, "12.50", "2026-03-02", null));
        BinResp personal = bytes("GET", "/v1/spaces/" + p.space() + "/finance-reports/cash-flow/export?from=2026-03-01&to=2026-03-31&compare=previous", token(p), null, null);
        assertThat(personal.status()).isEqualTo(200);
        String text = personal.text();
        assertThat(text).contains("INR,2026-03,2026-03-01,2026-03-31,true,0.00,12.50,0.00,12.50,-12.50");
        assertThat(text).contains("CHANGE_AMOUNT").contains("CHANGE_PERCENT");
        assertThat(text).doesNotContain("'-12.50");
        assertThat(count("select count(*) from finance_audit_events where tenant_id = :p0 and event_type = 'REPORT_EXPORTED'", p.space())).isEqualTo(1);
    }
}
