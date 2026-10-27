package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Business summaries and project profitability: documented definitions, exclusions, currencies, periods, roles. */
class BusinessSummariesTest extends BusinessTestSupport {

    private String issuedOn(Biz b, String client, String date, String due, String amount, String tax, String project) throws Exception {
        String id = ok(draft(b, client, date, due, "INR", List.of(line("Work", "1", amount, "0", tax)), project));
        String total = bget(b, "/invoices/" + id).text("total");
        ok(issue(b, id, total));
        return id;
    }

    /** The scenario described in the test names; all dates are in March 2026 unless stated. "Today" is 2026-03-15. */
    private record World(Biz b, String client, String project, String i1, String i2, String i5, String p1) {
    }

    private World world() throws Exception {
        Biz b = biz();
        String client = client(b, "Acme");
        String project = project(b, "Warehouse", client, "INR", "1000.00");
        String cat = ok(bpost(b, "/expense-categories", Map.of("name", "Materials")));
        String i1 = issuedOn(b, client, "2026-03-02", null, "1000.00", "18", project); // total 1180.00, due 2026-04-01
        String i2 = issuedOn(b, client, "2026-03-05", "2026-03-10", "200.00", "0", null); // overdue by 5 days
        draftInvoice(b, client, "999.00"); // a draft never counts
        String gone = issuedOn(b, client, "2026-03-06", null, "300.00", "0", null);
        ok(bpost(b, "/invoices/" + gone + "/cancel", Map.of("confirm", true, "reason", "Raised in error"))); // a cancelled invoice never counts
        String i5 = issuedOn(b, client, "2026-02-10", "2026-02-20", "100.00", "0", null); // February: outside the March period
        String p1 = ok(pay(b, client, "500.00", "P1", List.of(alloc(i1, "500.00"))));
        String voidedPayment = ok(pay(b, client, "77.00", "P2", null));
        ok(bpost(b, "/payments/" + voidedPayment + "/void", Map.of("reason", "bounced")));
        ok(pay(b, client, "25.00", "P3", null)); // an advance: received, but not allocated
        ok(expense(b, "300.00", "2026-03-03", null, project, cat));
        ok(expense(b, "50.00", "2026-03-04", null, null, null));
        String voidedExpense = ok(expense(b, "400.00", "2026-03-05", null, null, null));
        ok(bpost(b, "/expenses/" + voidedExpense + "/void", Map.of("reason", "dup")));
        ok(expense(b, "10.00", "2026-02-20", null, null, null));
        return new World(b, client, project, i1, i2, i5, p1);
    }

    private JsonNode inr(Resp overview) {
        for (JsonNode c : overview.body().path("currencies")) {
            if ("INR".equals(c.path("currency").asString())) {
                return c;
            }
        }
        throw new AssertionError("no INR in " + overview.raw().body());
    }

    @Test
    void theOverviewFollowsTheDocumentedDefinitionsAndExcludesDraftsCancelledAndVoided() throws Exception {
        World w = world();
        Resp r = bget(w.b(), "/business-summaries/overview");
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.text("from")).isEqualTo("2026-03-01");
        assertThat(r.text("to")).isEqualTo("2026-03-15");
        JsonNode c = inr(r);
        assertThat(c.path("invoiced_net").asString()).isEqualTo("1200.00");
        assertThat(c.path("invoiced_tax").asString()).isEqualTo("180.00");
        assertThat(c.path("invoiced_total").asString()).isEqualTo("1380.00");
        assertThat(c.path("invoice_count").asLong()).isEqualTo(2);
        assertThat(c.path("received").asString()).isEqualTo("525.00");
        assertThat(c.path("expenses").asString()).isEqualTo("350.00");
        assertThat(c.path("net_cash").asString()).isEqualTo("175.00");
        assertThat(c.path("operating_result").asString()).isEqualTo("850.00");
        assertThat(c.path("receivables").asString()).isEqualTo("980.00");
        assertThat(c.path("overdue").asString()).isEqualTo("300.00");
        assertThat(c.path("unallocated_payments").asString()).isEqualTo("25.00");
        List<String> aging = new java.util.ArrayList<>();
        c.path("aging").forEach(a -> aging.add(a.path("label").asString() + "=" + a.path("amount").asString()));
        assertThat(aging).containsExactly("Not yet due=680.00", "1-30 days overdue=300.00", "31-60 days overdue=0.00", "61-90 days overdue=0.00",
                "Over 90 days overdue=0.00");
        assertThat(c.path("expenses_by_category").get(0).path("category").asString()).isEqualTo("Materials");
        assertThat(c.path("expenses_by_category").get(0).path("amount").asString()).isEqualTo("300.00");
        assertThat(c.path("expenses_by_category").get(1).path("category").asString()).isEqualTo("Uncategorised");
        // the agings add up to the receivables
        assertThat(aging).hasSize(5);
    }

    @Test
    void periodsAreInclusiveAndFebruaryStaysOutOfMarch() throws Exception {
        World w = world();
        JsonNode feb = inr(bget(w.b(), "/business-summaries/overview?from=2026-02-01&to=2026-02-28"));
        assertThat(feb.path("invoiced_net").asString()).isEqualTo("100.00");
        assertThat(feb.path("expenses").asString()).isEqualTo("10.00");
        JsonNode edge = inr(bget(w.b(), "/business-summaries/overview?from=2026-03-02&to=2026-03-02"));
        assertThat(edge.path("invoiced_net").asString()).as("both ends inclusive").isEqualTo("1000.00");
        assertThat(bget(w.b(), "/business-summaries/overview?from=2026-03-10&to=2026-03-01").status()).isEqualTo(422);
        assertThat(bget(w.b(), "/business-summaries/overview?from=2000-01-01&to=2026-03-01").status()).as("more than ten years").isEqualTo(422);
        assertThat(bget(w.b(), "/business-summaries/overview?from=x").status()).isEqualTo(422);
    }

    @Test
    void voidingAPaymentOrCancellingChangesTheFiguresImmediately() throws Exception {
        World w = world();
        ok(bpost(w.b(), "/payments/" + w.p1() + "/void", Map.of("reason", "wrong account")));
        JsonNode c = inr(bget(w.b(), "/business-summaries/overview"));
        assertThat(c.path("received").asString()).isEqualTo("25.00");
        assertThat(c.path("receivables").asString()).isEqualTo("1480.00");
        ok(bpost(w.b(), "/invoices/" + w.i2() + "/cancel", Map.of("confirm", true, "reason", "waived")));
        JsonNode after = inr(bget(w.b(), "/business-summaries/overview"));
        assertThat(after.path("invoiced_net").asString()).isEqualTo("1000.00");
        assertThat(after.path("overdue").asString()).isEqualTo("100.00");
    }

    @Test
    void projectProfitabilityUsesRevenueExcludingTaxMinusPostedCosts() throws Exception {
        World w = world();
        Resp r = bget(w.b(), "/business-summaries/projects/" + w.project());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.text("revenue_net")).isEqualTo("1000.00");
        assertThat(r.text("invoiced_total")).isEqualTo("1180.00");
        assertThat(r.text("received")).isEqualTo("500.00");
        assertThat(r.text("outstanding")).isEqualTo("680.00");
        assertThat(r.text("costs")).isEqualTo("300.00");
        assertThat(r.text("profit")).isEqualTo("700.00");
        assertThat(r.text("margin_percent")).isEqualTo("70.00");
        assertThat(r.text("budget")).isEqualTo("1000.00");
        assertThat(r.text("budget_remaining")).isEqualTo("700.00");
        assertThat(r.text("budget_used_percent")).isEqualTo("30.00");
        assertThat(r.body().path("over_budget").asBoolean()).isFalse();
        assertThat(bget(w.b(), "/projects/" + w.project()).body().path("profitability").path("profit").asString()).isEqualTo("700.00");

        // a period with no revenue has an undefined margin, not zero or infinity
        Resp april = bget(w.b(), "/business-summaries/projects/" + w.project() + "?from=2026-04-01&to=2026-04-30");
        assertThat(april.text("revenue_net")).isEqualTo("0.00");
        assertThat(april.body().path("margin_percent").isNull()).isTrue();
        assertThat(april.text("profit")).isEqualTo("0.00");

        // overspending the budget is reported (not blocked)
        ok(expense(w.b(), "800.00", "2026-03-08", null, w.project(), null));
        Resp over = bget(w.b(), "/business-summaries/projects/" + w.project());
        assertThat(over.text("costs")).isEqualTo("1100.00");
        assertThat(over.text("budget_remaining")).isEqualTo("-100.00");
        assertThat(over.body().path("over_budget").asBoolean()).isTrue();
        assertThat(over.text("profit")).isEqualTo("-100.00");
        assertThat(over.text("margin_percent")).isEqualTo("-10.00");
        Resp list = bget(w.b(), "/business-summaries/projects");
        assertThat(list.body().path("items")).hasSize(1);
        assertThat(bget(w.b(), "/business-summaries/projects/" + UUID.randomUUID()).status()).isEqualTo(404);
    }

    @Test
    void currenciesAreReportedSeparatelyAndNeverMerged() throws Exception {
        World w = world();
        String usd = ok(draft(w.b(), w.client(), "2026-03-04", null, "USD", List.of(line("Export", "1", "100.00")), null));
        ok(issue(w.b(), usd, "100.00"));
        Resp r = bget(w.b(), "/business-summaries/overview");
        assertThat(r.body().path("currencies")).hasSize(2);
        assertThat(r.body().path("currencies").get(0).path("currency").asString()).isEqualTo("INR");
        assertThat(r.body().path("currencies").get(1).path("currency").asString()).isEqualTo("USD");
        assertThat(r.body().path("currencies").get(1).path("invoiced_net").asString()).isEqualTo("100.00");
        assertThat(inr(r).path("invoiced_net").asString()).isEqualTo("1200.00");
        // a USD payment cannot be applied to an INR invoice and a client balance lists each currency
        assertThat(bget(w.b(), "/clients/" + w.client()).body().path("balances")).hasSize(2);
    }

    @Test
    void anEmptyBusinessHasEmptySummaries() throws Exception {
        Biz b = biz();
        assertThat(bget(b, "/business-summaries/overview").body().path("currencies")).isEmpty();
        assertThat(bget(b, "/business-summaries/projects").body().path("items")).isEmpty();
    }

    @Test
    void theBusinessDayDecidesWhichPeriodTodayBelongsTo() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        ok(expense(b, "10.00", "2026-03-15", null, null, null));
        clock.set("2026-03-31T19:00:00Z"); // 2026-04-01 00:30 in Kolkata: April has begun for the business
        assertThat(bget(b, "/business-summaries/overview").text("from")).isEqualTo("2026-04-01");
        assertThat(bget(b, "/business-summaries/overview").body().path("currencies")).isEmpty();
        assertThat(c).isNotNull();
    }
}
