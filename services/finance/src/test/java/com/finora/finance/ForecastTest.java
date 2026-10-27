package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The baseline forecast: what it needs, what it refuses, and that it tells the truth about itself. */
class ForecastTest extends BusinessTestSupport {

    private static final String[] MONTHS = {"2025-09", "2025-10", "2025-11", "2025-12", "2026-01", "2026-02"};

    /** Income per window month; null means no transaction that month. Today in the tests is 2026-03-15. */
    private Person income(String... amounts) throws Exception {
        Person p = person();
        String acc = account(p, "Main", "BANK", "INR", "0.00", "2025-01-01");
        for (int i = 0; i < amounts.length; i++) {
            if (amounts[i] != null) {
                ok(tx(p, "INCOME", acc, amounts[i], MONTHS[i] + "-14", null));
            }
        }
        return p;
    }

    private JsonNode inr(Resp r) {
        for (JsonNode c : r.body().path("currencies")) {
            if ("INR".equals(c.path("currency").asString())) {
                return c;
            }
        }
        throw new AssertionError(r.raw().body());
    }

    @Test
    void aFullHistoryGivesAFlatEstimateWithAnIndicativeRangeAndHonestLabels() throws Exception {
        Person p = income("1000.00", "1100.00", "900.00", "1000.00", "1200.00", "800.00");
        Resp r = get(p, "/finance-forecast?metric=income");
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.text("kind")).isEqualTo("ESTIMATE");
        assertThat(r.text("label")).contains("not an actual result").contains("not a guarantee");
        assertThat(r.text("history_from")).isEqualTo("2025-09-01");
        assertThat(r.text("history_to")).isEqualTo("2026-02-28");
        assertThat(r.body().path("assumptions").toString()).contains("No trend, seasonality").contains("not a confidence interval").contains("nothing is converted");
        assertThat(r.body().path("ai_explanation").path("status").asString()).isEqualTo("UNAVAILABLE");
        JsonNode c = inr(r);
        assertThat(c.path("status").asString()).isEqualTo("ESTIMATE");
        assertThat(c.path("estimate_per_month").asString()).isEqualTo("1000.00");
        assertThat(c.path("median").asString()).isEqualTo("1000.00");
        assertThat(c.path("low").asString()).as("mean minus one population standard deviation (129.0994...)").isEqualTo("870.90");
        assertThat(c.path("high").asString()).isEqualTo("1129.10");
        assertThat(c.path("coverage").path("months_with_data").asInt()).isEqualTo(6);
        assertThat(c.path("coverage").path("ratio").asString()).isEqualTo("1.00");
        assertThat(c.path("confidence").asString()).isEqualTo("MODERATE");
        assertThat(c.path("warnings")).isEmpty();
        assertThat(c.path("history")).hasSize(6);
        assertThat(c.path("projection")).hasSize(3);
        assertThat(c.path("projection").get(0).path("month").asString()).as("the current month is the first month forecast").isEqualTo("2026-03");
        assertThat(c.path("projection").get(2).path("month").asString()).isEqualTo("2026-05");
        assertThat(c.path("projection").get(1).path("estimate").asString()).isEqualTo("1000.00");
    }

    @Test
    void theCurrentIncompleteMonthNeverEntersTheHistory() throws Exception {
        Person p = income("1000.00", "1000.00", "1000.00", "1000.00", "1000.00", "1000.00");
        String acc = jdbc.sql("select id::text from accounts where tenant_id = :t").param("t", p.space()).query(String.class).single();
        ok(tx(p, "INCOME", acc, "99999.00", "2026-03-10", null));
        assertThat(inr(get(p, "/finance-forecast?metric=income")).path("estimate_per_month").asString()).isEqualTo("1000.00");
    }

    @Test
    void tooLittleHistoryIsRefusedInsteadOfGuessed() throws Exception {
        Person p = income("1000.00", null, null, null, null, "1200.00");
        Resp r = get(p, "/finance-forecast?metric=income");
        JsonNode c = inr(r);
        assertThat(c.path("status").asString()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(c.path("message").asString()).contains("Only 2 of the last 6");
        assertThat(c.path("estimate_per_month").isNull()).isTrue();
        assertThat(c.path("projection")).isEmpty();
        assertThat(c.path("coverage").path("missing_months")).hasSize(4);
        assertThat(get(person(), "/finance-forecast?metric=income").body().path("currencies")).as("no data at all: nothing to forecast").isEmpty();
    }

    @Test
    void sparseHistoryIsUsedButMissingMonthsAreListedAndSmallCoverageLowersConfidence() throws Exception {
        Person sparse = income("100.00", null, "200.00", "300.00", null, "400.00");
        JsonNode c = inr(get(sparse, "/finance-forecast?metric=income"));
        assertThat(c.path("status").asString()).isEqualTo("ESTIMATE");
        assertThat(c.path("estimate_per_month").asString()).as("months without activity are missing, not zero").isEqualTo("250.00");
        List<String> missing = new ArrayList<>();
        c.path("coverage").path("missing_months").forEach(m -> missing.add(m.asString()));
        assertThat(missing).containsExactly("2025-10", "2026-01");
        assertThat(c.path("warnings").toString()).contains("2 month(s) had no activity");
        assertThat(c.path("confidence").asString()).isEqualTo("MODERATE");
        JsonNode thin = inr(get(income("100.00", null, "100.00", null, null, "100.00"), "/finance-forecast?metric=income"));
        assertThat(thin.path("status").asString()).isEqualTo("ESTIMATE");
        assertThat(thin.path("coverage").path("ratio").asString()).isEqualTo("0.50");
        assertThat(thin.path("confidence").asString()).isEqualTo("LOW");
    }

    @Test
    void outliersAreFlaggedKeptAndTheMedianIsShownAsAnAlternative() throws Exception {
        JsonNode c = inr(get(income("100.00", "100.00", "100.00", "100.00", "100.00", "1000.00"), "/finance-forecast?metric=income"));
        assertThat(c.path("estimate_per_month").asString()).isEqualTo("250.00");
        assertThat(c.path("median").asString()).isEqualTo("100.00");
        assertThat(c.path("history").get(5).path("outlier").asBoolean()).isTrue();
        assertThat(c.path("history").get(0).path("outlier").asBoolean()).isFalse();
        assertThat(c.path("confidence").asString()).isEqualTo("LOW");
        assertThat(c.path("warnings").toString()).contains("2 standard deviations").contains("median");
        // an estimate of an amount that cannot be negative never has a negative low end
        assertThat(c.path("low").asString()).isEqualTo("0.00");
    }

    @Test
    void roundingIsHalfEvenToTheCurrencyAndEachCurrencyIsForecastOnItsOwn() throws Exception {
        Person p = person();
        String acc = account(p, "Main", "BANK", "INR", "0.00", "2025-01-01");
        String usd = account(p, "Dollar", "BANK", "USD", "0.00", "2025-01-01");
        String[] values = {"10.01", "10.02", "10.02"};
        for (int i = 0; i < 3; i++) {
            ok(tx(p, "INCOME", acc, values[i], MONTHS[i + 3] + "-10", null));
        }
        ok(tx(p, "INCOME", usd, "50.00", "2026-02-10", null));
        Resp r = get(p, "/finance-forecast?metric=income&lookback_months=3&horizon_months=1");
        assertThat(inr(r).path("estimate_per_month").asString()).isEqualTo("10.02");
        assertThat(r.body().path("currencies")).hasSize(2);
        JsonNode usdForecast = r.body().path("currencies").get(1);
        assertThat(usdForecast.path("currency").asString()).isEqualTo("USD");
        assertThat(usdForecast.path("status").asString()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(inr(r).path("projection")).hasSize(1);
    }

    @Test
    void businessForecastsUseInvoicedReceivedExpensesAndNetCashAndExcludeVoidedMoney() throws Exception {
        Biz b = biz();
        String client = client(b, "Acme");
        String[] receipts = {"500.00", "600.00", "700.00"};
        for (int i = 0; i < 3; i++) {
            Resp r = bpost(b, "/payments", java.util.Map.of("client_id", client, "payment_date", MONTHS[i + 3] + "-10", "amount", receipts[i], "currency", "INR", "method", "CASH"));
            ok(r);
        }
        String voided = ok(bpost(b, "/payments", java.util.Map.of("client_id", client, "payment_date", "2025-12-20", "amount", "9999.00", "currency", "INR", "method", "CASH")));
        ok(bpost(b, "/payments/" + voided + "/void", java.util.Map.of("reason", "bounced")));
        for (int i = 0; i < 3; i++) {
            ok(expense(b, "100.00", MONTHS[i + 3] + "-12", null, null, null));
        }
        assertThat(inr(bget(b, "/finance-forecast?metric=received&lookback_months=3")).path("estimate_per_month").asString()).isEqualTo("600.00");
        assertThat(inr(bget(b, "/finance-forecast?lookback_months=3")).path("estimate_per_month").asString()).as("net_cash is the default: 600 - 100").isEqualTo("500.00");
        assertThat(inr(bget(b, "/finance-forecast?metric=expenses&lookback_months=3")).path("estimate_per_month").asString()).isEqualTo("100.00");
        assertThat(inr(bget(b, "/finance-forecast?metric=invoiced_net&lookback_months=3")).path("status").asString()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(bget(b, "/finance-forecast?metric=income").status()).as("a personal metric in a business workspace").isEqualTo(422);
    }

    @Test
    void parametersAreValidated() throws Exception {
        Person p = person();
        assertThat(get(p, "/finance-forecast?metric=bogus").status()).isEqualTo(422);
        assertThat(get(p, "/finance-forecast?lookback_months=2").status()).isEqualTo(422);
        assertThat(get(p, "/finance-forecast?lookback_months=13").status()).isEqualTo(422);
        assertThat(get(p, "/finance-forecast?horizon_months=0").status()).isEqualTo(422);
        assertThat(get(p, "/finance-forecast?horizon_months=7").status()).isEqualTo(422);
        assertThat(get(p, "/finance-forecast").status()).isEqualTo(200);
    }
}
