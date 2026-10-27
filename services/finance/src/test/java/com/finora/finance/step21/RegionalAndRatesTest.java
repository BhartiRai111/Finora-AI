package com.finora.finance.step21;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.finance.BusinessTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Regional settings, exchange-rate entry/lookup and their isolation and permissions. Today in these tests is 2026-03-15T10:00Z. */
class RegionalAndRatesTest extends BusinessTestSupport {

    private Resp rate(Person p, String base, String quote, String rate, String date) throws Exception {
        return post(p, "/finance-rates", Map.of("base_currency", base, "quote_currency", quote, "rate", rate, "rate_date", date, "source_note", "bank card rate"));
    }

    // ------------------------------------------------------------------ regional settings

    @Test
    void defaultsReproduceTheBehaviourBeforeTheSettingsExisted() throws Exception {
        Person p = person();
        Resp r = get(p, "/finance-regional");
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.text("timezone")).isEqualTo("UTC");
        assertThat(r.text("today")).isEqualTo("2026-03-15");
        assertThat(r.body().path("display_currency").isNull()).isTrue();
        assertThat(r.body().path("locale").isNull()).isTrue();
        assertThat(r.body().path("date_format").isNull()).isTrue();
        assertThat(r.body().path("fiscal_year_start_month").asInt()).isEqualTo(1);
        assertThat(r.text("reporting_period")).isEqualTo("MONTH");
        assertThat(r.body().path("rate_stale_after_days").asInt()).isEqualTo(7);
        assertThat(r.body().path("supported").path("locales").toString()).contains("en-IN").contains("en-US");
        Biz b = biz();
        Resp br = bget(b, "/finance-regional");
        assertThat(br.text("timezone")).isEqualTo("Asia/Kolkata");
        assertThat(br.text("base_currency")).isEqualTo("INR");
        assertThat(br.text("domain")).isEqualTo("BUSINESS");
    }

    @Test
    void settingsAreValidatedAndSavedPerWorkspaceWithoutAffectingOthers() throws Exception {
        Person a = person();
        Person other = person();
        Resp ok = put(a, "/finance-regional", Map.of("timezone", "Asia/Kolkata", "display_currency", "usd", "locale", "en-IN", "date_format", "dmy", "fiscal_year_start_month", 4,
                "reporting_period", "fiscal_year", "rate_stale_after_days", 3));
        assertThat(ok.status()).as(ok.raw().body()).isEqualTo(200);
        assertThat(ok.text("display_currency")).isEqualTo("USD");
        assertThat(ok.text("date_format")).isEqualTo("DMY");
        assertThat(ok.text("reporting_period")).isEqualTo("FISCAL_YEAR");
        assertThat(get(a, "/finance-regional").body().path("fiscal_year_start_month").asInt()).isEqualTo(4);
        Resp untouched = get(other, "/finance-regional");
        assertThat(untouched.text("timezone")).isEqualTo("UTC");
        assertThat(untouched.body().path("display_currency").isNull()).isTrue();
        assertThat(untouched.body().path("fiscal_year_start_month").asInt()).isEqualTo(1);
        for (Map<String, Object> bad : List.of(Map.<String, Object>of("timezone", "Mars/Olympus"), Map.<String, Object>of("display_currency", "XXX"), Map.<String, Object>of("locale", "xx-YY"),
                Map.<String, Object>of("date_format", "YMD"), Map.<String, Object>of("fiscal_year_start_month", 0), Map.<String, Object>of("fiscal_year_start_month", 13),
                Map.<String, Object>of("reporting_period", "WEEK"), Map.<String, Object>of("rate_stale_after_days", -1), Map.<String, Object>of("rate_stale_after_days", 366))) {
            assertThat(put(a, "/finance-regional", bad).status()).as(bad.toString()).isEqualTo(422);
        }
        assertThat(get(a, "/finance-regional").text("display_currency")).as("a rejected update changes nothing").isEqualTo("USD");
        Resp cleared = put(a, "/finance-regional", Map.of("clear_display_currency", true, "clear_locale", true, "clear_date_format", true));
        assertThat(cleared.body().path("display_currency").isNull()).isTrue();
        assertThat(cleared.body().path("locale").isNull()).isTrue();
        assertThat(cleared.text("timezone")).isEqualTo("Asia/Kolkata");
    }

    @Test
    void changingTheTimeZoneNeverRewritesStoredDatesOnlyWhatTodayAndBoundariesMean() throws Exception {
        Person p = person();
        String acc = account(p, "Main", "BANK", "INR", "0.00", "2025-01-01");
        ok(tx(p, "EXPENSE", acc, "10.00", "2026-02-28", null));
        ok(tx(p, "EXPENSE", acc, "20.00", "2026-03-01", null));
        clock.set("2026-03-31T20:00:00Z");
        assertThat(get(p, "/finance-regional").text("today")).isEqualTo("2026-03-31");
        ok(put(p, "/finance-regional", Map.of("timezone", "Asia/Kolkata")));
        assertThat(get(p, "/finance-regional").text("today")).as("01:30 on April 1st in Kolkata").isEqualTo("2026-04-01");
        List<String> dates = jdbc.sql("select occurred_on::text from transactions where tenant_id = :t order by occurred_on").param("t", p.space()).query(String.class).list();
        assertThat(dates).containsExactly("2026-02-28", "2026-03-01");
    }

    @Test
    void fiscalPeriodsComeFromTheWorkspacesOwnStartMonth() throws Exception {
        Person p = person();
        ok(put(p, "/finance-regional", Map.of("fiscal_year_start_month", 4)));
        JsonNode periods = get(p, "/finance-regional/periods").body().path("periods");
        assertThat(periods.path("this_fiscal_year").path("from").asString()).isEqualTo("2025-04-01");
        assertThat(periods.path("this_fiscal_year").path("to").asString()).isEqualTo("2026-03-15");
        assertThat(periods.path("this_fiscal_year").path("end").asString()).isEqualTo("2026-03-31");
        assertThat(periods.path("last_fiscal_year").path("from").asString()).isEqualTo("2024-04-01");
        assertThat(periods.path("last_fiscal_quarter").path("from").asString()).isEqualTo("2025-10-01");
        assertThat(get(person(), "/finance-regional/periods").body().path("periods").path("this_fiscal_year").path("from").asString()).as("another workspace keeps January").isEqualTo("2026-01-01");
    }

    @Test
    void regionalSettingsFollowTheRoleMatrix() throws Exception {
        Biz b = biz();
        UUID user = UUID.randomUUID();
        for (String role : List.of("OWNER", "ADMIN")) {
            assertThat(as(b, user, role, "PUT", "/finance-regional", Map.of("locale", "en-GB")).status()).as(role + " write").isEqualTo(200);
        }
        for (String role : List.of("ACCOUNTANT", "AUDITOR")) {
            assertThat(as(b, user, role, "GET", "/finance-regional", null).status()).as(role + " read").isEqualTo(200);
            assertThat(as(b, user, role, "PUT", "/finance-regional", Map.of("locale", "en-US")).status()).as(role + " write").isEqualTo(403);
        }
        for (String role : List.of("VIEWER", "CONTRIBUTOR", "NOT_A_ROLE")) {
            assertThat(as(b, user, role, "PUT", "/finance-regional", Map.of("locale", "en-US")).status()).as(role + " write").isEqualTo(403);
        }
        assertThat(bget(b, "/finance-regional").text("locale")).isEqualTo("en-GB");
        Person p = person();
        for (String role : List.of("ADMIN", "VIEWER", "ACCOUNTANT")) {
            assertThat(raw("PUT", base(p) + "/finance-regional", token(p, p.space(), role, List.of("workspace:personal")), Map.of("locale", "en-US")).status()).as("personal " + role).isEqualTo(403);
        }
        Person stranger = person();
        assertThat(raw("GET", base(p) + "/finance-regional", token(stranger), null).status()).as("another workspace's token").isEqualTo(404);
        assertThat(raw("PUT", base(p) + "/finance-regional", token(stranger), Map.of("locale", "en-US")).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ exchange rates

    @Test
    void ratesAreEnteredWithTheirSourceAndDateAndNeverEditedOnlyVoided() throws Exception {
        Person p = person();
        Resp r = rate(p, "usd", "inr", "83.2150", "2026-03-10");
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        assertThat(r.text("base_currency")).isEqualTo("USD");
        assertThat(r.text("quote_currency")).isEqualTo("INR");
        assertThat(r.text("rate")).isEqualTo("83.215");
        assertThat(r.text("source")).isEqualTo("USER_SUPPLIED");
        assertThat(r.text("rate_date")).isEqualTo("2026-03-10");
        assertThat(r.text("status")).isEqualTo("ACTIVE");
        assertThat(rate(p, "USD", "INR", "84", "2026-03-10").status()).as("one active rate per pair and date").isEqualTo(409);
        Resp voided = post(p, "/finance-rates/" + r.id() + "/void", Map.of("reason", "typo"));
        assertThat(voided.status()).as(voided.raw().body()).isEqualTo(200);
        assertThat(voided.text("status")).isEqualTo("VOIDED");
        assertThat(post(p, "/finance-rates/" + r.id() + "/void", Map.of("reason", "again")).status()).isEqualTo(409);
        assertThat(post(p, "/finance-rates/" + UUID.randomUUID() + "/void", Map.of("reason", "x")).status()).isEqualTo(404);
        assertThat(post(p, "/finance-rates/" + r.id() + "/void", Map.of()).status()).isEqualTo(422);
        assertThat(rate(p, "USD", "INR", "83.50", "2026-03-10").status()).as("a corrected rate can follow a void").isEqualTo(201);
        assertThat(get(p, "/finance-rates").body().path("items")).hasSize(1);
        assertThat(get(p, "/finance-rates?include_voided=true").body().path("items")).hasSize(2);
        assertThat(get(p, "/finance-rates?base=EUR").body().path("items")).isEmpty();
        assertThat(count("select count(*) from exchange_rates where tenant_id = :p0 and status = 'VOIDED'", p.space())).isEqualTo(1);
        assertThat(jdbc.sql("select rate::text from exchange_rates where tenant_id = :t and id = :id").param("t", p.space()).param("id", UUID.fromString(r.id())).query(String.class).single())
                .as("the voided row keeps its original value").startsWith("83.2150");
    }

    @Test
    void invalidRatesAndPairsAreRejected() throws Exception {
        Person p = person();
        for (String[] bad : new String[][] {{"USD", "INR", "0", "2026-03-10"}, {"USD", "INR", "-83", "2026-03-10"}, {"USD", "INR", "abc", "2026-03-10"}, {"USD", "INR", "83.12345678901", "2026-03-10"},
                {"USD", "INR", "1000000001", "2026-03-10"}, {"USD", "USD", "1", "2026-03-10"}, {"USD", "XXX", "1", "2026-03-10"}, {"usd", "", "1", "2026-03-10"}, {"USD", "INR", "83", "2026-03-16"},
                {"USD", "INR", "83", "1999-12-31"}, {"USD", "INR", "83", "not-a-date"}, {"USD", "INR", "83", ""}}) {
            Resp r = rate(p, bad[0], bad[1], bad[2], bad[3]);
            assertThat(r.status()).as(String.join(" ", bad)).isEqualTo(422);
        }
        assertThat(post(p, "/finance-rates", Map.of()).status()).isEqualTo(422);
        assertThat(count("select count(*) from exchange_rates where tenant_id = :p0", p.space())).isZero();
        assertThat(rate(p, "USD", "INR", "83", "2026-03-15").status()).as("today is allowed").isEqualTo(201);
    }

    @Test
    void lookupSelectsTheLatestRateOnOrBeforeTheDateAndReportsStaleAndMissingHonestly() throws Exception {
        Person p = person();
        ok(rate(p, "USD", "INR", "82.00", "2026-01-31"));
        ok(rate(p, "USD", "INR", "83.00", "2026-02-28"));
        ok(rate(p, "USD", "INR", "84.00", "2026-03-12"));
        Resp mar = get(p, "/finance-rates/lookup?base=USD&quote=INR&date=2026-03-13");
        assertThat(mar.text("status")).isEqualTo("APPLIED");
        assertThat(mar.body().path("rate").asDouble()).isEqualTo(84.0);
        assertThat(mar.text("rate_date")).isEqualTo("2026-03-12");
        assertThat(mar.body().path("age_days").asInt()).isEqualTo(1);
        Resp feb = get(p, "/finance-rates/lookup?base=USD&quote=INR&date=2026-03-05");
        assertThat(feb.body().path("rate").asDouble()).as("a later rate is never used for an earlier date").isEqualTo(83.0);
        assertThat(feb.body().path("age_days").asInt()).isEqualTo(5);
        assertThat(get(p, "/finance-rates/lookup?base=USD&quote=INR&date=2026-03-09").text("status")).as("9 days old is beyond 7").isEqualTo("STALE");
        assertThat(get(p, "/finance-rates/lookup?base=USD&quote=INR&date=2026-03-09").text("message")).contains("beyond the 7-day threshold");
        Resp missing = get(p, "/finance-rates/lookup?base=USD&quote=INR&date=2026-01-15");
        assertThat(missing.text("status")).isEqualTo("MISSING");
        assertThat(missing.body().path("rate").isNull()).isTrue();
        assertThat(get(p, "/finance-rates/lookup?base=INR&quote=USD&date=2026-03-13").text("status")).as("the inverse is never derived").isEqualTo("MISSING");
        assertThat(get(p, "/finance-rates/lookup?base=EUR&quote=INR").text("status")).isEqualTo("MISSING");
        assertThat(get(p, "/finance-rates/lookup?base=INR&quote=INR").text("status")).isEqualTo("SAME");
        assertThat(get(p, "/finance-rates/lookup?base=USD&quote=XXX").status()).isEqualTo(422);
        ok(put(p, "/finance-regional", Map.of("rate_stale_after_days", 30)));
        assertThat(get(p, "/finance-rates/lookup?base=USD&quote=INR&date=2026-03-09").text("status")).as("the threshold is the workspace's own").isEqualTo("APPLIED");
        // voiding removes a rate from use
        String id = get(p, "/finance-rates?base=USD&quote=INR").body().path("items").get(0).path("id").asString();
        ok(post(p, "/finance-rates/" + id + "/void", Map.of("reason", "wrong")));
        assertThat(get(p, "/finance-rates/lookup?base=USD&quote=INR&date=2026-03-13").body().path("rate").asDouble()).isEqualTo(83.0);
    }

    @Test
    void ratesBelongToOneWorkspaceAndTheStatusAdmitsThereIsNoAutomaticSource() throws Exception {
        Person a = person();
        Person other = person();
        ok(rate(a, "USD", "INR", "83.00", "2026-03-10"));
        assertThat(get(other, "/finance-rates").body().path("items")).isEmpty();
        assertThat(get(other, "/finance-rates/lookup?base=USD&quote=INR&date=2026-03-10").text("status")).isEqualTo("MISSING");
        assertThat(raw("GET", base(a) + "/finance-rates", token(other), null).status()).isEqualTo(404);
        assertThat(raw("POST", base(a) + "/finance-rates", token(other), Map.of("base_currency", "USD", "quote_currency", "INR", "rate", "1", "rate_date", "2026-03-10")).status()).isEqualTo(404);
        Resp status = get(a, "/finance-rates/status");
        assertThat(status.body().path("automatic_source").path("status").asString()).isEqualTo("NOT_CONFIGURED");
        assertThat(status.body().path("automatic_source").path("message").asString()).contains("No exchange-rate provider has been approved");
        assertThat(status.body().path("manual_entry").path("source").asString()).isEqualTo("USER_SUPPLIED");
        assertThat(status.body().path("manual_entry").path("can_write").asBoolean()).isTrue();
    }

    @Test
    void ratePermissionsFollowCompanySettingsRights() throws Exception {
        Biz b = biz();
        UUID user = UUID.randomUUID();
        Map<String, Object> body = Map.of("base_currency", "USD", "quote_currency", "INR", "rate", "83", "rate_date", "2026-03-10");
        assertThat(as(b, b.owner(), "OWNER", "POST", "/finance-rates", body).status()).isEqualTo(201);
        assertThat(as(b, user, "ACCOUNTANT", "GET", "/finance-rates", null).status()).isEqualTo(200);
        assertThat(as(b, user, "ACCOUNTANT", "GET", "/finance-rates/status", null).body().path("manual_entry").path("can_write").asBoolean()).isFalse();
        for (String role : List.of("ACCOUNTANT", "AUDITOR", "VIEWER", "CONTRIBUTOR", "NOT_A_ROLE")) {
            assertThat(as(b, user, role, "POST", "/finance-rates", Map.of("base_currency", "EUR", "quote_currency", "INR", "rate", "90", "rate_date", "2026-03-10")).status()).as(role).isEqualTo(403);
        }
        for (String role : List.of("VIEWER", "CONTRIBUTOR")) {
            assertThat(as(b, user, role, "GET", "/finance-rates", null).status()).as(role + " read").isEqualTo(403);
        }
        assertThat(count("select count(*) from finance_audit_events where tenant_id = :p0 and event_type = 'EXCHANGE_RATE_ADDED'", b.space())).isEqualTo(1);
    }
}
