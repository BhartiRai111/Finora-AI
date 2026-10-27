package com.finora.finance.step21;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.finance.BusinessTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Reports shown in a reporting currency: traceable, never silently combined, never from an unusable rate, and never writing anything. Today is 2026-03-15. */
class ConversionTest extends BusinessTestSupport {

    private String usdAcc;
    private String inrAcc;

    private Person withIncome() throws Exception {
        Person p = person();
        inrAcc = account(p, "Rupee", "BANK", "INR", "0.00", "2025-01-01");
        usdAcc = account(p, "Dollar", "BANK", "USD", "0.00", "2025-01-01");
        ok(tx(p, "INCOME", inrAcc, "1000.00", "2026-01-10", null));
        ok(tx(p, "INCOME", usdAcc, "100.10", "2026-01-10", null));
        ok(tx(p, "INCOME", usdAcc, "200.20", "2026-02-12", null));
        return p;
    }

    private void rate(Person p, String base, String quote, String rate, String date) throws Exception {
        ok(post(p, "/finance-rates", Map.of("base_currency", base, "quote_currency", quote, "rate", rate, "rate_date", date)));
    }

    private JsonNode currency(Resp r, String code) {
        for (JsonNode c : r.body().path("conversion").path("currencies")) {
            if (code.equals(c.path("currency").asString())) {
                return c;
            }
        }
        throw new AssertionError("no " + code + " in " + r.raw().body());
    }

    private static final String Q = "/finance-reports/cash-flow/converted?from=2026-01-01&to=2026-02-28&reporting_currency=";

    @Test
    void eachPeriodIsConvertedFromItsOriginalAtTheRateOfItsLastDayAndEveryRateIsTraceable() throws Exception {
        Person p = withIncome();
        rate(p, "USD", "INR", "83.0000", "2026-01-31");
        rate(p, "USD", "INR", "84.0000", "2026-02-28");
        Resp r = get(p, Q + "INR");
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        JsonNode original = r.body().path("report").path("currencies");
        assertThat(original).hasSize(2);
        JsonNode usdOriginal = original.get(1);
        assertThat(usdOriginal.path("currency").asString()).isEqualTo("USD");
        assertThat(usdOriginal.path("totals").path("income").asString()).as("the original is untouched").isEqualTo("300.30");
        JsonNode usd = currency(r, "USD");
        assertThat(usd.path("status").asString()).isEqualTo("CONVERTED");
        JsonNode jan = usd.path("rows").get(0);
        JsonNode feb = usd.path("rows").get(1);
        assertThat(jan.path("period").asString()).isEqualTo("2026-01");
        assertThat(jan.path("values").path("income").asString()).isEqualTo("8308.30");
        assertThat(jan.path("rate").path("rate").asString()).isEqualTo("83");
        assertThat(jan.path("rate").path("rate_date").asString()).isEqualTo("2026-01-31");
        assertThat(jan.path("rate").path("source").asString()).isEqualTo("USER_SUPPLIED");
        assertThat(jan.path("rate_as_of").asString()).isEqualTo("2026-01-31");
        assertThat(feb.path("values").path("income").asString()).isEqualTo("16816.80");
        assertThat(feb.path("rate").path("rate").asString()).isEqualTo("84");
        assertThat(usd.path("totals").path("income").asString()).as("the sum of the converted periods").isEqualTo("25125.10");
        JsonNode inr = currency(r, "INR");
        assertThat(inr.path("status").asString()).isEqualTo("SAME");
        assertThat(inr.path("totals").path("income").asString()).isEqualTo("1000.00");
        assertThat(r.body().path("conversion").path("combined_totals").path("income").asString()).isEqualTo("26125.10");
        assertThat(r.body().path("conversion").path("reporting_currency").asString()).isEqualTo("INR");
        assertThat(r.body().path("conversion").path("notes").toString()).contains("never stored").contains("USER_SUPPLIED");
    }

    @Test
    void aMissingRateMakesThatCurrencyUnavailableAndBlocksAnyCombinedTotal() throws Exception {
        Person p = withIncome();
        rate(p, "USD", "INR", "83.0000", "2026-01-31");
        Resp r = get(p, Q + "INR");
        assertThat(r.status()).isEqualTo(200);
        JsonNode usd = currency(r, "USD");
        assertThat(usd.path("status").asString()).as("Feb 28 is 28 days after the only rate").isEqualTo("UNAVAILABLE");
        assertThat(usd.path("message").asString()).contains("Not converted").contains("2026-02").contains("original USD figures above are unaffected");
        assertThat(usd.path("rows")).isEmpty();
        assertThat(usd.path("totals")).isEmpty();
        assertThat(r.body().path("conversion").path("combined_totals").isNull()).isTrue();
        assertThat(r.body().path("conversion").path("combined_unavailable_reason").asString()).contains("never added together without a valid rate");
        assertThat(currency(r, "INR").path("status").asString()).isEqualTo("SAME");
        assertThat(r.body().path("report").path("currencies").get(1).path("totals").path("income").asString()).isEqualTo("300.30");
        Resp none = get(withIncomeNoRates(), Q + "INR");
        assertThat(currency(none, "USD").path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(currency(none, "USD").path("message").asString()).contains("No USD/INR rate dated on or before");
    }

    private Person withIncomeNoRates() throws Exception {
        return withIncome();
    }

    @Test
    void aStaleRateIsNotUsedUnlessTheCallerExplicitlyAcceptsItAndIsThenFlaggedEverywhere() throws Exception {
        Person p = withIncome();
        rate(p, "USD", "INR", "83.0000", "2026-01-31");
        Resp strict = get(p, Q + "INR");
        assertThat(currency(strict, "USD").path("status").asString()).isEqualTo("UNAVAILABLE");
        Resp lax = get(p, Q + "INR&allow_stale=true");
        JsonNode usd = currency(lax, "USD");
        assertThat(usd.path("status").asString()).isEqualTo("CONVERTED_WITH_STALE_RATES");
        assertThat(usd.path("message").asString()).contains("stale");
        JsonNode feb = usd.path("rows").get(1);
        assertThat(feb.path("rate").path("status").asString()).isEqualTo("STALE");
        assertThat(feb.path("rate").path("rate_date").asString()).isEqualTo("2026-01-31");
        assertThat(feb.path("rate").path("age_days").asInt()).isEqualTo(28);
        assertThat(feb.path("values").path("income").asString()).isEqualTo("16616.60");
        assertThat(lax.body().path("conversion").path("allow_stale").asBoolean()).isTrue();
        ok(put(p, "/finance-regional", Map.of("rate_stale_after_days", 30)));
        assertThat(currency(get(p, Q + "INR"), "USD").path("status").asString()).as("a workspace that accepts older rates by setting").isEqualTo("CONVERTED");
    }

    @Test
    void aZeroDecimalOrThreeDecimalReportingCurrencyRoundsToItsOwnMinorUnit() throws Exception {
        Person p = withIncome();
        rate(p, "USD", "JPY", "149.5", "2026-01-31");
        rate(p, "USD", "JPY", "150.25", "2026-02-28");
        rate(p, "INR", "JPY", "1.8", "2026-01-31");
        rate(p, "INR", "JPY", "1.8", "2026-02-28");
        Resp r = get(p, Q + "JPY");
        assertThat(currency(r, "USD").path("rows").get(0).path("values").path("income").asString()).as("100.10 x 149.5 = 14964.95").isEqualTo("14965");
        assertThat(currency(r, "USD").path("rows").get(1).path("values").path("income").asString()).as("200.20 x 150.25 = 30080.05").isEqualTo("30080");
        assertThat(currency(r, "INR").path("rows").get(0).path("values").path("income").asString()).isEqualTo("1800");
        assertThat(r.body().path("conversion").path("combined_totals").path("income").asString()).isEqualTo("46845");
    }

    @Test
    void historicalRatesAndLaterRatesNeverChangeEarlierPeriodsAndRepeatedRequestsChangeNothing() throws Exception {
        Person p = withIncome();
        rate(p, "USD", "INR", "83.0000", "2026-01-31");
        rate(p, "USD", "INR", "84.0000", "2026-02-28");
        String before = get(p, Q + "INR").body().path("conversion").toString();
        rate(p, "USD", "INR", "99.0000", "2026-03-10");
        assertThat(get(p, Q + "INR").body().path("conversion").toString()).as("a rate dated after the periods is not used").isEqualTo(before);
        long rates = count("select count(*) from exchange_rates where tenant_id = :p0", p.space());
        BigDecimalSnapshot snap = new BigDecimalSnapshot(jdbc.sql("select string_agg(amount::text || currency, ',' order by id) from transactions where tenant_id = :t").param("t", p.space()).query(String.class).single());
        for (int i = 0; i < 4; i++) {
            assertThat(get(p, Q + "INR").body().path("conversion").path("combined_totals").path("income").asString()).isEqualTo("26125.10");
        }
        assertThat(count("select count(*) from exchange_rates where tenant_id = :p0", p.space())).isEqualTo(rates);
        assertThat(jdbc.sql("select string_agg(amount::text || currency, ',' order by id) from transactions where tenant_id = :t").param("t", p.space()).query(String.class).single()).isEqualTo(snap.value());
        assertThat(jdbc.sql("select string_agg(amount::text || currency, ',' order by id) from transactions where tenant_id = :t").param("t", p.space()).query(String.class).single()).contains("100.1000USD");
    }

    private record BigDecimalSnapshot(String value) {
    }

    @Test
    void theDatabaseItselfRefusesZeroNegativeOrSelfPairRates() throws Exception {
        Person p = person();
        for (String bad : List.of("0", "-1", "1000000001")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("insert into exchange_rates (id, tenant_id, base_currency, quote_currency, rate, rate_date, created_by, created_at) values (:id, :t, 'USD', 'INR', :r, '2026-01-01', :u, now())")
                    .param("id", java.util.UUID.randomUUID()).param("t", p.space()).param("r", new java.math.BigDecimal(bad)).param("u", p.userId()).update()).as(bad).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("insert into exchange_rates (id, tenant_id, base_currency, quote_currency, rate, rate_date, created_by, created_at) values (:id, :t, 'USD', 'USD', 1, '2026-01-01', :u, now())")
                .param("id", java.util.UUID.randomUUID()).param("t", p.space()).param("u", p.userId()).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("delete from exchange_rates where tenant_id = :t").param("t", p.space()).update()).as("rows cannot be deleted by the application role")
                .isInstanceOfAny(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void requestsAreValidatedAndFollowReportPermissions() throws Exception {
        Person p = withIncome();
        assertThat(get(p, "/finance-reports/cash-flow/converted?reporting_currency=XXX").status()).isEqualTo(422);
        assertThat(get(p, "/finance-reports/cash-flow/converted?reporting_currency=").status()).isEqualTo(422);
        assertThat(get(p, "/finance-reports/nope/converted?reporting_currency=INR").status()).isEqualTo(404);
        assertThat(get(p, "/finance-reports/business-performance/converted?reporting_currency=INR").status()).as("a business report in a personal workspace").isEqualTo(404);
        assertThat(get(p, "/finance-reports/cash-flow/converted?reporting_currency=INR&from=2026-03-01&to=2026-01-01").status()).isEqualTo(422);
        Person stranger = person();
        assertThat(raw("GET", base(p) + Q + "INR", token(stranger), null).status()).isEqualTo(404);
        Biz b = biz();
        assertThat(as(b, java.util.UUID.randomUUID(), "VIEWER", "GET", "/finance-reports/business-performance/converted?reporting_currency=INR", null).status()).isEqualTo(403);
        assertThat(as(b, java.util.UUID.randomUUID(), "ACCOUNTANT", "GET", "/finance-reports/business-performance/converted?reporting_currency=INR", null).status()).isEqualTo(200);
    }

    @Test
    void everyReportKindConvertsItsMoneyColumnsOnlyAndLeavesCountsAndPercentagesAlone() throws Exception {
        Biz b = biz();
        String client = client(b, "Acme");
        String usdInvoice = ok(draft(b, client, "2026-01-05", "2026-02-05", "USD", List.of(line("Work", "1", "100.00")), null));
        ok(issue(b, usdInvoice, "100.00"));
        String inrInvoice = ok(draft(b, client, "2026-01-06", "2026-02-06", "INR", List.of(line("Work", "1", "500.00")), null));
        ok(issue(b, inrInvoice, "500.00"));
        ok(post2(b, "USD", "INR", "80.0000", "2026-03-15"));
        Resp ar = as(b, b.owner(), "OWNER", "GET", "/finance-reports/receivables/converted?reporting_currency=INR", null);
        assertThat(ar.status()).as(ar.raw().body()).isEqualTo(200);
        JsonNode usd = null;
        for (JsonNode c : ar.body().path("conversion").path("currencies")) {
            if ("USD".equals(c.path("currency").asString())) {
                usd = c;
            }
        }
        assertThat(usd.path("status").asString()).isEqualTo("CONVERTED");
        assertThat(usd.path("totals_rate").path("rate").asString()).isEqualTo("80");
        assertThat(usd.path("totals").path("outstanding").asString()).isEqualTo("8000.00");
        assertThat(usd.path("totals").path("overdue").asString()).isEqualTo("8000.00");
        assertThat(usd.path("totals").has("invoices")).as("a count is not money").isFalse();
        assertThat(ar.body().path("conversion").path("combined_totals").path("outstanding").asString()).isEqualTo("8500.00");
        Resp pp = as(b, b.owner(), "OWNER", "GET", "/finance-reports/project-profitability/converted?reporting_currency=INR&from=2026-01-01&to=2026-03-15", null);
        assertThat(pp.status()).isEqualTo(200);
    }

    private Resp post2(Biz b, String base, String quote, String rate, String date) throws Exception {
        return as(b, b.owner(), "OWNER", "POST", "/finance-rates", Map.of("base_currency", base, "quote_currency", quote, "rate", rate, "rate_date", date));
    }

    @Test
    void theCsvExportKeepsTheOriginalBlockAndAddsAConvertedBlockWithFormulaProtection() throws Exception {
        Biz b = biz();
        String evil = client(b, "=HYPERLINK(\"http://evil.example\",\"x\")");
        ok(issue(b, ok(draft(b, evil, "2026-01-05", "2026-02-05", "USD", List.of(line("Work", "1", "100.00")), null)), "100.00"));
        ok(post2(b, "USD", "INR", "80.0000", "2026-03-15"));
        BinResp csv = bytes("GET", "/v1/spaces/" + b.space() + "/finance-reports/receivables-by-client/export?reporting_currency=INR", btoken(b, b.owner(), "OWNER"), null, null);
        assertThat(csv.status()).isEqualTo(200);
        String text = csv.text();
        assertThat(text).contains("currency,period").contains("converted_to,source_currency,period,status,rate,rate_date,rate_source,rate_age_days");
        assertThat(text).contains("USD,\"'=HYPERLINK").doesNotContain("\r\n=HYPERLINK").doesNotContain(",=HYPERLINK");
        assertThat(text).contains("INR,USD,").contains("8000.00").contains("USER_SUPPORTED".replace("SUPPORTED", "SUPPLIED"));
        assertThat(text).as("the original block still lists the original amount").contains("100.00");
        BinResp plain = bytes("GET", "/v1/spaces/" + b.space() + "/finance-reports/receivables-by-client/export", btoken(b, b.owner(), "OWNER"), null, null);
        assertThat(plain.text()).as("without a reporting currency the export is exactly what it was").doesNotContain("converted_to");
        BinResp noRate = bytes("GET", "/v1/spaces/" + b.space() + "/finance-reports/receivables-by-client/export?reporting_currency=EUR", btoken(b, b.owner(), "OWNER"), null, null);
        assertThat(noRate.status()).isEqualTo(200);
        assertThat(noRate.text()).contains("EUR,USD,,UNAVAILABLE");
        assertThat(bytes("GET", "/v1/spaces/" + b.space() + "/finance-reports/receivables-by-client/export?reporting_currency=INR", btoken(b, java.util.UUID.randomUUID(), "ADMIN"), null, null).status()).as("export needs the export right").isEqualTo(403);
    }

    @Test
    void forecastsAndComparisonsKeepCurrenciesSeparateAndNeverConvert() throws Exception {
        Person p = person();
        String inr = account(p, "Rupee", "BANK", "INR", "0.00", "2025-01-01");
        String usd = account(p, "Dollar", "BANK", "USD", "0.00", "2025-01-01");
        for (String m : new String[] {"2025-09", "2025-10", "2025-11", "2025-12", "2026-01", "2026-02"}) {
            ok(tx(p, "INCOME", inr, "1000.00", m + "-14", null));
            ok(tx(p, "INCOME", usd, "10.00", m + "-14", null));
        }
        rate(p, "USD", "INR", "83.0000", "2026-02-28");
        Resp f = get(p, "/finance-forecast?metric=income");
        assertThat(f.body().path("currencies")).hasSize(2);
        assertThat(f.body().path("currencies").get(0).path("currency").asString()).isEqualTo("INR");
        assertThat(f.body().path("currencies").get(0).path("estimate_per_month").asString()).isEqualTo("1000.00");
        assertThat(f.body().path("currencies").get(1).path("estimate_per_month").asString()).as("USD stays USD: no conversion even though a rate exists").isEqualTo("10.00");
        assertThat(f.body().path("assumptions").toString()).contains("nothing is converted");
        Resp cmp = get(p, "/finance-reports/cash-flow?from=2026-02-01&to=2026-02-28&compare=previous");
        assertThat(cmp.body().path("comparison").path("currencies")).hasSize(2);
    }
}
