package com.finora.finance.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.finance.gst.GstCodes;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The assistant with Step 21 data: conversions, GST summaries and fiscal periods come from backend calculations, and what is unavailable or unsupported is said plainly. */
class AssistantStep21Test extends AssistantTestSupport {

    private static final String SUPPLIER = "27AAPFU0939F1ZV";

    private Person twoCurrencies() throws Exception {
        Person p = person();
        String inr = account(p, "Rupee", "BANK", "INR", "0.00", "2025-01-01");
        String usd = account(p, "Dollar", "BANK", "USD", "0.00", "2025-01-01");
        ok(tx(p, "EXPENSE", inr, "100.00", "2026-02-10", null));
        ok(tx(p, "EXPENSE", usd, "10.00", "2026-02-11", null));
        return p;
    }

    private void rate(Person p, String base, String quote, String rate, String date) throws Exception {
        ok(post(p, "/finance-rates", Map.of("base_currency", base, "quote_currency", quote, "rate", rate, "rate_date", date)));
    }

    @Test
    void aConvertedViewComesFromTheBackendRateAndKeepsTheOriginalCurrenciesVisible() throws Exception {
        Person p = twoCurrencies();
        rate(p, "USD", "INR", "83.0000", "2026-02-28");
        Resp r = ask(p, "How much did I spend last month converted to INR?");
        assertAnswered(r);
        assertThat(r.body().path("period").path("from").asString()).isEqualTo("2026-02-01");
        assertThat(cell(section(r, "in INR", "INR"), "Net spending (expenses minus refunds)")).as("the original INR figure is still shown").isEqualTo("100.00");
        assertThat(cell(section(r, "in USD", "USD"), "Net spending (expenses minus refunds)")).isEqualTo("10.00");
        JsonNode usdConv = section(r, "USD converted to INR", "INR");
        assertThat(usdConv.path("kind").asString()).isEqualTo("CONVERTED");
        assertThat(cell(usdConv, "Net spending (expenses minus refunds)")).isEqualTo("830.00");
        assertThat(usdConv.path("notes").toString()).contains("rate 83").contains("dated 2026-02-28").contains("USER_SUPPLIED");
        JsonNode combined = section(r, "Combined total in INR", "INR");
        assertThat(cell(combined, "Net spending (expenses minus refunds)")).isEqualTo("930.00");
        assertThat(r.text("summary")).contains("Converted view").contains("930.00");
        assertThat(r.body().path("sources").toString()).contains("CONVERSION");
        assertThat(r.body().path("limitations").toString()).contains("never stored").contains("USER_SUPPLIED");
    }

    @Test
    void withoutAUsableRateTheAssistantSaysSoAndInventsNoConversionOrTotal() throws Exception {
        Person p = twoCurrencies();
        Resp r = ask(p, "How much did I spend last month converted to INR?");
        assertAnswered(r);
        JsonNode unavailable = section(r, "USD to INR: unavailable", "INR");
        assertThat(unavailable.path("rows")).isEmpty();
        assertThat(unavailable.path("notes").toString()).contains("No USD/INR rate dated on or before 2026-02").contains("original USD figures above are unaffected");
        assertThat(r.raw().body()).doesNotContain("Combined total");
        assertThat(r.body().path("limitations").toString()).contains("never added together without a valid rate");
        assertThat(r.text("summary")).contains("could not be converted");
        rate(p, "USD", "INR", "83.0000", "2026-01-01");
        Resp stale = ask(p, "How much did I spend last month converted to INR?");
        assertThat(stale.raw().body()).as("a stale rate is not silently used").contains("unavailable").doesNotContain("830.00");
    }

    @Test
    void anUnsupportedConversionTargetIsAskedBackAndTheCurrencyFilterStillWorks() throws Exception {
        Person p = twoCurrencies();
        assertThat(ask(p, "How much did I spend last month converted to XYZ?").text("status")).as("not a supported code: treated as a plain question").isIn("ANSWERED", "NEEDS_CLARIFICATION");
        Resp usd = ask(p, "How much did I spend last month in USD?");
        assertThat(usd.body().path("sections")).hasSize(1);
        assertThat(usd.raw().body()).doesNotContain("CONVERTED");
    }

    @Test
    void fiscalYearPhrasesUseTheWorkspacesOwnStartMonth() throws Exception {
        Person p = person();
        String acc = account(p, "Main", "BANK", "INR", "0.00", "2024-01-01");
        ok(tx(p, "EXPENSE", acc, "10.00", "2025-05-10", null));
        ok(tx(p, "EXPENSE", acc, "20.00", "2025-02-10", null));
        ok(put(p, "/finance-regional", Map.of("fiscal_year_start_month", 4)));
        Resp thisFy = ask(p, "How much did I spend this fiscal year?");
        assertThat(thisFy.body().path("period").path("from").asString()).isEqualTo("2025-04-01");
        assertThat(thisFy.body().path("period").path("to").asString()).isEqualTo("2026-03-15");
        assertThat(cell(section(thisFy, "in INR", "INR"), "Expenses")).isEqualTo("10.00");
        Resp lastFy = ask(p, "How much did I spend last financial year?");
        assertThat(lastFy.body().path("period").path("from").asString()).isEqualTo("2024-04-01");
        assertThat(lastFy.body().path("period").path("to").asString()).isEqualTo("2025-03-31");
        assertThat(cell(section(lastFy, "in INR", "INR"), "Expenses")).isEqualTo("20.00");
        Person calendar = person();
        assertThat(ask(calendar, "How much did I spend this fiscal year?").body().path("period").path("from").asString()).isEqualTo("2026-01-01");
    }

    private Biz registeredBusiness() throws Exception {
        Biz b = biz();
        ok(as(b, b.owner(), "OWNER", "PUT", "/finance-gst/registration", Map.of("gst_registered", true, "gstin", SUPPLIER)));
        return b;
    }

    @Test
    void gstAnswersComeFromTheSummaryAndNeverClaimFilingOrCompliance() throws Exception {
        Biz b = registeredBusiness();
        String c = client(b, "Buyer");
        String inv = ok(draft(b, c, "2026-02-10", "2026-03-10", "INR", List.of(line("Work", "1", "1000.00", "0", "18")), null));
        ok(issue(b, inv, "1180.00"));
        ok(as(b, b.owner(), "OWNER", "PUT", "/finance-gst/invoices/" + inv, Map.of("place_of_supply", "27")));
        Resp r = bask(b, "Show my GST summary for last month");
        assertAnswered(r);
        assertThat(r.text("intent")).isEqualTo("gst_summary");
        JsonNode sales = section(r, "GST on sales", "INR");
        assertThat(cell(sales, "CGST")).isEqualTo("90.00");
        assertThat(cell(sales, "SGST")).isEqualTo("90.00");
        assertThat(cell(sales, "IGST")).isEqualTo("0.00");
        assertThat(cell(sales, "Taxable amount")).isEqualTo("1000.00");
        assertThat(cell(sales, "Total tax")).isEqualTo("180.00");
        JsonNode direct = bget(b, "/finance-gst/summary?from=2026-02-01&to=2026-02-28").body().path("sales");
        assertThat(cell(sales, "Total tax")).isEqualTo(direct.path("total_tax").asString());
        assertThat(r.body().path("limitations").toString()).contains("NOT an official GST return").contains("Nothing was filed").contains("UTGST");
        assertThat(r.raw().body()).doesNotContainIgnoringCase("compliant").doesNotContain(SUPPLIER);
        Resp igst = bask(b, "What is my total CGST SGST and IGST for 2026-02-01 to 2026-02-28?");
        assertAnswered(igst);
        // no GST details at all in another period: no figure is invented
        Resp none = bask(b, "Show my GST summary for january");
        assertThat(none.text("status")).isEqualTo("NO_DATA");
        assertThat(none.text("summary")).contains("Nothing was estimated or calculated");
        // personal workspaces have no GST
        Person p = person();
        assertThat(ask(p, "Show my GST summary").text("status")).isEqualTo("UNSUPPORTED");
        assertThat(ask(p, "Show my GST summary").text("summary")).contains("only in business workspaces");
    }

    @Test
    void filingAndAccountingRequestsAreRefusedBecauseTheAssistantIsReadOnly() throws Exception {
        Biz b = registeredBusiness();
        long before = audit(b.space(), "AI_REQUEST_REFUSED");
        for (String q : List.of("File my GST return for last month", "submit the GSTR-3B", "e-file my gst returns", "sync my invoices to Tally")) {
            Resp r = bask(b, q);
            assertThat(r.text("status")).as(q).isEqualTo("UNSUPPORTED");
            assertThat(r.body().path("sections")).as(q).isEmpty();
            assertThat(r.text("summary")).as(q).doesNotContainIgnoringCase("filed successfully").doesNotContainIgnoringCase("synchronised successfully");
        }
        assertThat(bask(b, "Can you sync with QuickBooks?").text("summary")).contains("No accounting provider is approved or configured").contains("I cannot do that");
        assertThat(bask(b, "Connect to Zoho Books").text("summary")).contains("No accounting provider");
        assertThat(audit(b.space(), "AI_REQUEST_REFUSED")).isGreaterThanOrEqualTo(before + 3);
        assertThat(count("select count(*) from accounting_sync_runs where tenant_id = :p0", b.space())).isZero();
    }

    @Test
    void whatIfAndForecastKeepEachCurrencyOnItsOwnEvenWhenARateExists() throws Exception {
        Person p = person();
        String inr = account(p, "Rupee", "BANK", "INR", "0.00", "2025-01-01");
        String usd = account(p, "Dollar", "BANK", "USD", "0.00", "2025-01-01");
        for (String m : new String[] {"2025-09", "2025-10", "2025-11", "2025-12", "2026-01", "2026-02"}) {
            ok(tx(p, "INCOME", inr, "1000.00", m + "-14", null));
            ok(tx(p, "EXPENSE", inr, "400.00", m + "-15", null));
            ok(tx(p, "INCOME", usd, "10.00", m + "-14", null));
            ok(tx(p, "EXPENSE", usd, "4.00", m + "-15", null));
        }
        rate(p, "USD", "INR", "83.0000", "2026-02-28");
        Resp w = post(p, "/finance-assistant/what-if", Map.of("expense_change_percent", "10", "revenue_change_percent", "-20", "horizon_months", 1));
        assertAnswered(w);
        JsonNode inrSc = section(w, "Hypothetical scenario in INR", "INR");
        JsonNode usdSc = section(w, "Hypothetical scenario in USD", "USD");
        assertThat(inrSc.path("rows").get(0).get(2).asString()).isEqualTo("800.00");
        assertThat(inrSc.path("rows").get(0).get(4).asString()).isEqualTo("440.00");
        assertThat(usdSc.path("rows").get(0).get(2).asString()).as("USD stays USD, no conversion").isEqualTo("8.00");
        assertThat(usdSc.path("rows").get(0).get(4).asString()).isEqualTo("4.40");
        assertThat(w.body().path("assumptions").toString()).contains("nothing is converted");
        Resp f = ask(p, "Forecast my income for the next 2 months");
        assertThat(section(f, "Estimate of income", "INR").path("rows").get(0).get(1).asString()).isEqualTo("1000.00");
        assertThat(section(f, "Estimate of income", "USD").path("rows").get(0).get(1).asString()).isEqualTo("10.00");
    }
}
