package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.finance.core.FinanceContext;
import com.finora.finance.recurring.RecurringService;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Recurring transactions, bills and subscriptions. "Today" is 2026-03-15 unless a test moves the clock. */
class RecurringAndCommitmentsTest extends FinanceTestSupport {

    @Autowired RecurringService recurring;

    private Map<String, Object> rule(String kind, String account, String amount, String frequency, String start, Object... extra) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("kind", kind, "account_id", account, "amount", amount, "frequency", frequency, "start_date", start));
        for (int i = 0; i < extra.length; i += 2) {
            body.put((String) extra[i], extra[i + 1]);
        }
        return body;
    }

    private int run(Person p) throws Exception {
        Resp r = post(p, "/recurring-rules/run", Map.of());
        assertThat(r.status()).isEqualTo(200);
        return r.body().path("created").asInt();
    }

    private long txCount(Person p, String ruleId) {
        return count("select count(*) from transactions where tenant_id = :p0 and recurring_rule_id = :p1", p.space(), UUID.fromString(ruleId));
    }

    // ---- recurring ----

    @Test
    void monthlyRuleOnThe31stGeneratesDraftsCatchesUpOnceAndFollowsTheCalendar() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "1000.00");
        String id = ok(post(p, "/recurring-rules", rule("EXPENSE", a, "50.00", "MONTHLY", "2026-01-31")));
        assertThat(get(p, "/recurring-rules").body().path("items").get(0).path("next_occurrence").asString()).isEqualTo("2026-01-31");
        assertThat(run(p)).as("Jan 31 and Feb 28 are due; Mar 31 is not").isEqualTo(2);
        assertThat(run(p)).as("running again creates nothing").isZero();
        assertThat(txCount(p, id)).isEqualTo(2L);
        assertThat(balance(p, a)).as("drafts do not count until confirmed").isEqualTo("1000.00");
        JsonNode drafts = get(p, "/transactions?status=DRAFT").body().path("items");
        assertThat(drafts).hasSize(2);
        assertThat(drafts.get(0).path("occurred_on").asString()).isEqualTo("2026-02-28");
        assertThat(drafts.get(1).path("occurred_on").asString()).isEqualTo("2026-01-31");
        String draftId = drafts.get(0).path("id").asString();
        assertThat(post(p, "/transactions/" + draftId + "/confirm", Map.of()).text("status")).isEqualTo("POSTED");
        assertThat(balance(p, a)).isEqualTo("950.00");
        assertThat(post(p, "/transactions/" + draftId + "/confirm", Map.of()).status()).isEqualTo(409);
        assertThat(get(p, "/recurring-rules").body().path("items").get(0).path("next_occurrence").asString()).isEqualTo("2026-03-31");
        clock.set("2026-04-01T10:00:00Z");
        assertThat(run(p)).isEqualTo(1);
        assertThat(get(p, "/transactions?status=DRAFT&from=2026-03-31&to=2026-03-31").body().path("items")).as("March 31, not the 28th").hasSize(1);
        clock.set("2026-05-01T10:00:00Z");
        assertThat(run(p)).isEqualTo(1);
        assertThat(get(p, "/transactions?status=DRAFT&from=2026-04-30&to=2026-04-30").body().path("items")).hasSize(1);
    }

    @Test
    void autoPostRulesPostImmediatelyAndMissedOccurrencesAreCaughtUpExactlyOnce() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "0");
        String id = ok(post(p, "/recurring-rules", rule("INCOME", a, "10.00", "WEEKLY", "2026-01-05", "auto_post", true)));
        assertThat(run(p)).as("ten Mondays from Jan 5 to Mar 9").isEqualTo(10);
        assertThat(balance(p, a)).isEqualTo("100.00");
        assertThat(run(p)).isZero();
        assertThat(get(p, "/transactions?status=DRAFT").body().path("items")).isEmpty();
        assertThat(txCount(p, id)).isEqualTo(10L);
        assertThat(count("select count(distinct occurrence_index) from transactions where recurring_rule_id = :p0", UUID.fromString(id))).isEqualTo(10L);
    }

    @Test
    void parallelGenerationNeverCreatesAnOccurrenceTwice() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "0");
        String id = ok(post(p, "/recurring-rules", rule("EXPENSE", a, "1.00", "DAILY", "2026-03-01")));
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            calls.add(() -> post(p, "/recurring-rules/run", Map.of()).body().path("created").asInt());
        }
        int total = concurrently(calls).stream().mapToInt(Integer::intValue).sum();
        assertThat(total).as("15 days, created once across all runs").isEqualTo(15);
        assertThat(txCount(p, id)).isEqualTo(15L);
    }

    @Test
    void resumingSkipsWhatFellDueWhilePausedAndEndDatesStopGeneration() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "0");
        String id = ok(post(p, "/recurring-rules", rule("EXPENSE", a, "5.00", "MONTHLY", "2026-03-01")));
        run(p);
        assertThat(post(p, "/recurring-rules/" + id + "/pause", Map.of()).body().path("active").asBoolean()).isFalse();
        assertThat(post(p, "/recurring-rules/" + id + "/pause", Map.of()).status()).isEqualTo(409);
        clock.set("2026-05-20T10:00:00Z");
        assertThat(run(p)).as("paused rules generate nothing").isZero();
        Resp resumed = post(p, "/recurring-rules/" + id + "/resume", Map.of());
        assertThat(resumed.text("next_occurrence")).as("April and May were skipped, not back-filled").isEqualTo("2026-06-01");
        assertThat(run(p)).isZero();
        assertThat(txCount(p, id)).isEqualTo(1L);

        String ended = ok(post(p, "/recurring-rules", rule("EXPENSE", a, "5.00", "MONTHLY", "2026-01-15", "end_date", "2026-02-15")));
        assertThat(run(p)).isEqualTo(2);
        assertThat(txCount(p, ended)).isEqualTo(2L);
        assertThat(get(p, "/recurring-rules").body().path("items").toString()).contains("\"next_occurrence\":null");
    }

    @Test
    void ruleChangesOnlyAffectFutureOccurrencesAndAreVersioned() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "0");
        String id = ok(post(p, "/recurring-rules", rule("EXPENSE", a, "10.00", "MONTHLY", "2026-02-01", "auto_post", true)));
        run(p);
        assertThat(patch(p, "/recurring-rules/" + id, Map.of("amount", "20.00", "version", 0)).text("amount")).isEqualTo("20.00");
        assertThat(patch(p, "/recurring-rules/" + id, Map.of("amount", "30.00", "version", 0)).status()).isEqualTo(409);
        assertThat(patch(p, "/recurring-rules/" + id, Map.of("amount", "1.001", "version", 1)).status()).isEqualTo(422);
        assertThat(balance(p, a)).as("already generated transactions keep their amount").isEqualTo("-20.00");
        clock.set("2026-04-02T10:00:00Z");
        run(p);
        assertThat(balance(p, a)).as("the new amount applies from April").isEqualTo("-40.00");
    }

    @Test
    void occurrencesBeforeTheAccountExistedAreSkippedAndArchivedAccountsReceiveNothing() throws Exception {
        Person p = person();
        String a = account(p, "Late", "BANK", "INR", "0", "2026-03-01");
        String id = ok(post(p, "/recurring-rules", rule("INCOME", a, "1.00", "WEEKLY", "2026-02-01", "auto_post", true)));
        assertThat(run(p)).as("Mar 1, 8 and 15 only").isEqualTo(3);
        post(p, "/accounts/" + a + "/archive", Map.of());
        clock.set("2026-03-30T10:00:00Z");
        assertThat(run(p)).as("closed account: nothing posted").isZero();
        post(p, "/accounts/" + a + "/restore", Map.of());
        assertThat(run(p)).as("caught up after reopening: Mar 22 and 29").isEqualTo(2);
        assertThat(txCount(p, id)).isEqualTo(5L);
    }

    @Test
    void recurringRuleInputIsValidated() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "0");
        String inc = category(p, "Salary", "INCOME");
        assertThat(post(p, "/recurring-rules", rule("TRANSFER", a, "1.00", "MONTHLY", "2026-03-01")).status()).isEqualTo(422);
        assertThat(post(p, "/recurring-rules", rule("EXPENSE", a, "1.00", "FORTNIGHTLY", "2026-03-01")).status()).isEqualTo(422);
        assertThat(post(p, "/recurring-rules", rule("EXPENSE", a, "1.00", "MONTHLY", "2026-03-01", "interval_count", 0)).status()).isEqualTo(422);
        assertThat(post(p, "/recurring-rules", rule("EXPENSE", a, "1.00", "MONTHLY", "2026-03-01", "end_date", "2026-02-01")).status()).isEqualTo(422);
        assertThat(post(p, "/recurring-rules", rule("EXPENSE", a, "0", "MONTHLY", "2026-03-01")).status()).isEqualTo(422);
        assertThat(post(p, "/recurring-rules", rule("EXPENSE", a, "1.00", "MONTHLY", "2026-03-01", "category_id", inc)).status()).isEqualTo(422);
        assertThat(post(p, "/recurring-rules", rule("EXPENSE", UUID.randomUUID().toString(), "1.00", "MONTHLY", "2026-03-01")).status()).isEqualTo(422);
        assertThat(post(p, "/recurring-rules", rule("EXPENSE", a, "1.00", "MONTHLY", "2030-03-01")).status()).isEqualTo(422);
    }

    @Test
    void theSchedulerPathUsesTheProfilesOwnLocalDate() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "0");
        put(p, "/finance-settings", Map.of("timezone", "Pacific/Auckland"));
        ok(post(p, "/recurring-rules", rule("EXPENSE", a, "2.00", "MONTHLY", "2026-04-01", "auto_post", true)));
        clock.set("2026-03-31T11:30:00Z"); // 2026-04-01 00:30 in Auckland
        FinanceContext utc = FinanceContext.system(p.space(), ZoneId.of("UTC"), clock);
        assertThat(recurring.generateDueAsSystem(utc)).as("still March 31 in UTC").isZero();
        FinanceContext nz = FinanceContext.system(p.space(), ZoneId.of("Pacific/Auckland"), clock);
        assertThat(recurring.generateDueAsSystem(nz)).isEqualTo(1);
        assertThat(recurring.generateDueAsSystem(nz)).isZero();
        assertThat(count("select count(*) from finance_audit_events where tenant_id = :p0 and actor_id = :p1 and event_type = 'RECURRING_GENERATED'", p.space(), FinanceContext.SYSTEM_ACTOR)).isEqualTo(1L);
    }

    // ---- bills and subscriptions ----

    private Map<String, Object> commitment(String name, String amount, String frequency, String firstDue, Object... extra) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("name", name, "amount", amount, "currency", "INR", "frequency", frequency, "first_due_date", firstDue));
        for (int i = 0; i < extra.length; i += 2) {
            body.put((String) extra[i], extra[i + 1]);
        }
        return body;
    }

    @Test
    void aOneOffBillIsPaidOnlyByAnExplicitActionAndOnlyOnce() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "500.00");
        String rent = category(p, "Rent", "EXPENSE");
        Resp bill = post(p, "/bills", commitment("Electricity", "120.00", "ONCE", "2026-03-10", "category_id", rent, "account_id", a));
        assertThat(bill.status()).isEqualTo(201);
        assertThat(bill.body().path("overdue").asBoolean()).as("due Mar 10, today Mar 15").isTrue();
        assertThat(get(p, "/bills?due_within_days=30").body().path("items")).hasSize(1);
        assertThat(balance(p, a)).as("nothing is paid automatically").isEqualTo("500.00");
        Resp paid = post(p, "/bills/" + bill.id() + "/pay", Map.of("paid_on", "2026-03-14"));
        assertThat(paid.status()).as(paid.raw().body()).isEqualTo(201);
        assertThat(paid.body().path("item").path("status").asString()).isEqualTo("PAID");
        assertThat(balance(p, a)).isEqualTo("380.00");
        JsonNode tx = get(p, "/transactions/" + paid.text("transaction_id")).body();
        assertThat(tx.path("kind").asString()).isEqualTo("EXPENSE");
        assertThat(tx.path("category_id").asString()).isEqualTo(rent);
        assertThat(tx.path("commitment_id").asString()).isEqualTo(bill.id());
        assertThat(post(p, "/bills/" + bill.id() + "/pay", Map.of()).status()).as("already paid").isEqualTo(409);
        assertThat(get(p, "/bills?due_within_days=30").body().path("items")).as("paid bills are no longer upcoming").isEmpty();
        assertThat(count("select count(*) from transactions where commitment_id = :p0", UUID.fromString(bill.id()))).isEqualTo(1L);
    }

    @Test
    void subscriptionsReportAnnualisedCostPerCurrencyAndAdvanceWhenPaid() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "1000.00");
        Resp netflix = post(p, "/subscriptions", commitment("Streaming", "199.00", "MONTHLY", "2026-03-01", "account_id", a));
        Resp weekly = post(p, "/subscriptions", commitment("Paper", "10.00", "WEEKLY", "2026-03-02"));
        assertThat(netflix.text("annualized_cost")).isEqualTo("2388.00");
        assertThat(netflix.text("monthly_equivalent")).isEqualTo("199.00");
        assertThat(weekly.text("annualized_cost")).isEqualTo("520.00");
        assertThat(weekly.text("monthly_equivalent")).as("520 / 12 = 43.333.. rounded half-even").isEqualTo("43.33");
        Resp list = get(p, "/subscriptions");
        JsonNode totals = list.body().path("totals").get(0);
        assertThat(totals.path("annualized_cost").asString()).isEqualTo("2908.00");
        assertThat(totals.path("monthly_equivalent").asString()).isEqualTo("242.33");
        assertThat(post(p, "/subscriptions", commitment("Bad", "5.00", "ONCE", "2026-03-01")).status()).as("a subscription must repeat").isEqualTo(422);
        assertThat(netflix.body().path("overdue").asBoolean()).isTrue();

        Resp paid = post(p, "/subscriptions/" + netflix.id() + "/pay", Map.of("paid_on", "2026-03-01"));
        assertThat(paid.status()).isEqualTo(201);
        assertThat(paid.body().path("item").path("next_due_date").asString()).isEqualTo("2026-04-01");
        assertThat(paid.body().path("item").path("overdue").asBoolean()).isFalse();
        assertThat(balance(p, a)).isEqualTo("801.00");
        assertThat(postNoKey(p, "/subscriptions/" + netflix.id() + "/pay", Map.of()).status()).isEqualTo(400);
        Resp skipped = post(p, "/subscriptions/" + netflix.id() + "/skip", Map.of());
        assertThat(skipped.text("next_due_date")).isEqualTo("2026-05-01");
        assertThat(balance(p, a)).as("skipping records no payment").isEqualTo("801.00");
    }

    @Test
    void retriedAndConcurrentPaymentsNeverDoubleChargeOneDueDate() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "1000.00");
        String id = ok(post(p, "/subscriptions", commitment("Gym", "100.00", "MONTHLY", "2026-03-01", "account_id", a)));
        Resp first = postKeyed(p, "/subscriptions/" + id + "/pay", Map.of("paid_on", "2026-03-02"), "pay-1");
        Resp retry = postKeyed(p, "/subscriptions/" + id + "/pay", Map.of("paid_on", "2026-03-02"), "pay-1");
        assertThat(retry.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(retry.text("transaction_id")).isEqualTo(first.text("transaction_id"));
        assertThat(balance(p, a)).isEqualTo("900.00");
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(() -> post(p, "/subscriptions/" + id + "/pay", Map.of("paid_on", "2026-03-03")).status());
        }
        concurrently(calls);
        long payments = count("select count(*) from transactions where commitment_id = :p0", UUID.fromString(id));
        long distinct = count("select count(distinct occurrence_index) from transactions where commitment_id = :p0", UUID.fromString(id));
        assertThat(distinct).as("each payment is for a different due date").isEqualTo(payments);
        assertThat(count("select due_index from commitments where id = :p0", UUID.fromString(id))).isEqualTo(payments);
    }

    @Test
    void statusChangesAreGuardedAndCancelledItemsCannotBePaid() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "100.00");
        String id = ok(post(p, "/subscriptions", commitment("Mag", "10.00", "MONTHLY", "2026-03-20", "account_id", a)));
        assertThat(post(p, "/subscriptions/" + id + "/resume", Map.of()).status()).isEqualTo(409);
        assertThat(post(p, "/subscriptions/" + id + "/pause", Map.of()).text("status")).isEqualTo("PAUSED");
        assertThat(post(p, "/subscriptions/" + id + "/pay", Map.of()).status()).as("paused").isEqualTo(409);
        assertThat(post(p, "/subscriptions/" + id + "/resume", Map.of()).text("status")).isEqualTo("ACTIVE");
        assertThat(post(p, "/subscriptions/" + id + "/cancel", Map.of()).text("status")).isEqualTo("CANCELLED");
        assertThat(post(p, "/subscriptions/" + id + "/pay", Map.of()).status()).isEqualTo(409);
        assertThat(post(p, "/subscriptions/" + id + "/pause", Map.of()).status()).isEqualTo(409);
        assertThat(get(p, "/subscriptions").body().path("totals")).as("cancelled items are not in the totals").isEmpty();
        assertThat(get(p, "/bills/" + id).status()).as("a subscription is not reachable as a bill").isEqualTo(404);
    }

    @Test
    void paymentsRespectCurrenciesAndDates() throws Exception {
        Person p = person();
        String inr = bank(p, "Main", "100.00");
        String usd = account(p, "Dollar", "BANK", "USD", "100.00", "2026-01-01");
        String id = ok(post(p, "/bills", commitment("Fee", "10.00", "ONCE", "2026-03-20")));
        assertThat(post(p, "/bills/" + id + "/pay", Map.of("account_id", usd)).status()).as("USD account for an INR bill").isEqualTo(422);
        assertThat(post(p, "/bills/" + id + "/pay", Map.of("account_id", inr, "paid_on", "2026-03-16")).status()).as("future payment date").isEqualTo(422);
        assertThat(post(p, "/bills/" + id + "/pay", Map.of()).status()).as("no account given or defaulted").isEqualTo(422);
        assertThat(post(p, "/bills/" + id + "/pay", Map.of("account_id", inr, "amount", "9.995")).status()).isEqualTo(422);
        assertThat(post(p, "/bills/" + id + "/pay", Map.of("account_id", inr, "amount", "9.50")).status()).as("a different amount can be paid").isEqualTo(201);
        assertThat(balance(p, inr)).isEqualTo("90.50");
    }
}
