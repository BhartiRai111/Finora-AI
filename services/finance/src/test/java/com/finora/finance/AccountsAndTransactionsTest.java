package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Accounts, transactions, transfers, refunds, editing, voiding, filtering and pagination against real PostgreSQL. */
class AccountsAndTransactionsTest extends FinanceTestSupport {

    // ---- accounts ----

    @Test
    void anAccountsBalanceIsItsOpeningBalanceAndTheExactSumOfPostedTransactions() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "1000.00");
        assertThat(balance(p, a)).isEqualTo("1000.00");
        ok(tx(p, "INCOME", a, "0.10", "2026-03-01", null));
        ok(tx(p, "INCOME", a, "0.20", "2026-03-02", null));
        assertThat(balance(p, a)).as("0.10 + 0.20 is exactly 0.30").isEqualTo("1000.30");
        ok(tx(p, "EXPENSE", a, "250.75", "2026-03-03", null));
        assertThat(balance(p, a)).isEqualTo("749.55");
        assertThat(get(p, "/accounts/" + a).body().path("liability").asBoolean()).isFalse();
    }

    @Test
    void liabilitiesShowTheAmountOwedAndMoveTheOtherWay() throws Exception {
        Person p = person();
        String card = account(p, "Card", "CREDIT_CARD", "INR", "500.00", "2026-01-01");
        String bank = bank(p, "Main", "5000.00");
        ok(tx(p, "EXPENSE", card, "300.00", "2026-03-01", null));
        assertThat(balance(p, card)).as("spending raises what is owed").isEqualTo("800.00");
        ok(post(p, "/transfers", Map.of("from_account_id", bank, "to_account_id", card, "amount", "200.00", "occurred_on", "2026-03-02")));
        assertThat(balance(p, card)).as("paying the card lowers what is owed").isEqualTo("600.00");
        assertThat(balance(p, bank)).isEqualTo("4800.00");
        assertThat(get(p, "/accounts/" + card).body().path("liability").asBoolean()).isTrue();
    }

    @Test
    void accountInputIsValidatedPerCurrency() throws Exception {
        Person p = person();
        Map<String, Object> base = Map.of("name", "X", "type", "BANK", "currency", "INR", "opening_balance", "10.00");
        assertThat(post(p, "/accounts", Map.of("name", "X", "type", "BANK", "currency", "XXX", "opening_balance", "1")).status()).isEqualTo(422);
        assertThat(post(p, "/accounts", Map.of("name", "X", "type", "BANK", "currency", "INR", "opening_balance", "1.005")).status()).isEqualTo(422);
        assertThat(post(p, "/accounts", Map.of("name", "X", "type", "BANK", "currency", "JPY", "opening_balance", "10.5")).status()).isEqualTo(422);
        assertThat(post(p, "/accounts", Map.of("name", "X", "type", "SPACESHIP", "currency", "INR", "opening_balance", "1")).status()).isEqualTo(422);
        assertThat(post(p, "/accounts", Map.of("name", "", "type", "BANK", "currency", "INR")).status()).isEqualTo(422);
        assertThat(post(p, "/accounts", Map.of("name", "Loan", "type", "LOAN", "currency", "INR", "opening_balance", "-5")).status())
                .as("a liability's opening balance is what is owed").isEqualTo(422);
        assertThat(post(p, "/accounts", Map.of("name", "Yen", "type", "CASH", "currency", "JPY", "opening_balance", "1500")).status()).isEqualTo(201);
        assertThat(post(p, "/accounts", Map.of("name", "Dinar", "type", "CASH", "currency", "KWD", "opening_balance", "1.234")).status()).isEqualTo(201);
        assertThat(post(p, "/accounts", base).status()).isEqualTo(201);
        Resp dup = post(p, "/accounts", Map.of("name", "x", "type", "CASH", "currency", "INR"));
        assertThat(dup.status()).as("names are unique case-insensitively among active accounts").isEqualTo(409);
        JsonNode items = get(p, "/accounts").body().path("items");
        assertThat(items).hasSize(3);
    }

    @Test
    void renamingUsesOptimisticVersionsAndArchivingKeepsHistory() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "10.00");
        Resp renamed = patch(p, "/accounts/" + a, Map.of("name", "Primary", "version", 0));
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.body().path("version").asInt()).isEqualTo(1);
        assertThat(patch(p, "/accounts/" + a, Map.of("name", "Stale", "version", 0)).status()).isEqualTo(409);
        assertThat(patch(p, "/accounts/" + a, Map.of("name", "NoVersion")).status()).isEqualTo(422);
        ok(tx(p, "INCOME", a, "5.00", "2026-03-01", null));
        assertThat(post(p, "/accounts/" + a + "/archive", Map.of()).body().path("archived").asBoolean()).isTrue();
        assertThat(get(p, "/accounts").body().path("items")).as("archived accounts leave the pickers").isEmpty();
        assertThat(get(p, "/accounts?include_archived=true").body().path("items")).hasSize(1);
        assertThat(tx(p, "INCOME", a, "1.00", "2026-03-02", null).status()).as("nothing is posted to an archived account").isEqualTo(422);
        assertThat(count("select count(*) from transactions where account_id = :p0", UUID.fromString(a))).isEqualTo(1L);
        assertThat(post(p, "/accounts/" + a + "/restore", Map.of()).body().path("archived").asBoolean()).isFalse();
        assertThat(balance(p, a)).isEqualTo("15.00");
    }

    @Test
    void adjustmentsRecordTheExactDifferenceAsAnExplicitNonIncomeEntry() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "100.00");
        ok(tx(p, "EXPENSE", a, "30.00", "2026-03-01", null));
        Resp r = post(p, "/accounts/" + a + "/adjustments", Map.of("new_balance", "75.50", "as_of", "2026-03-10", "note", "Statement balance"));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.text("difference")).isEqualTo("5.50");
        assertThat(r.text("source")).isEqualTo("manual");
        assertThat(balance(p, a)).isEqualTo("75.50");
        Resp none = post(p, "/accounts/" + a + "/adjustments", Map.of("new_balance", "75.50"));
        assertThat(none.status()).isEqualTo(422);
        assertThat(postNoKey(p, "/accounts/" + a + "/adjustments", Map.of("new_balance", "80.00")).status()).as("idempotency key required").isEqualTo(400);
        JsonNode cashflow = get(p, "/summaries/cashflow?from=2026-03-01&to=2026-03-31").body().path("currencies").get(0);
        assertThat(cashflow.path("income").asString()).as("an adjustment is not income").isEqualTo("0.00");
        assertThat(cashflow.path("gross_expense").asString()).isEqualTo("30.00");
    }

    // ---- transactions ----

    @Test
    void transactionsAreValidatedAtTheFieldLevel() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "0");
        String inc = category(p, "Salary", "INCOME");
        String exp = category(p, "Food", "EXPENSE");
        assertThat(tx(p, "BOGUS", a, "1.00", "2026-03-01", null).status()).isEqualTo(422);
        assertThat(tx(p, "EXPENSE", a, "0.00", "2026-03-01", null).status()).isEqualTo(422);
        assertThat(tx(p, "EXPENSE", a, "-3.00", "2026-03-01", null).status()).isEqualTo(422);
        assertThat(tx(p, "EXPENSE", a, "1.001", "2026-03-01", null).status()).isEqualTo(422);
        assertThat(tx(p, "EXPENSE", a, "12", "2026-03-01", null).status()).as("whole numbers are fine").isEqualTo(201);
        assertThat(tx(p, "EXPENSE", a, "1.00", "03/01/2026", null).status()).isEqualTo(422);
        assertThat(tx(p, "EXPENSE", a, "1.00", "2025-12-31", null).status()).as("before the account existed").isEqualTo(422);
        assertThat(tx(p, "EXPENSE", a, "1.00", "2027-06-01", null).status()).as("more than a year ahead").isEqualTo(422);
        assertThat(tx(p, "EXPENSE", a, "1.00", "2026-03-01", inc).status()).as("income category on an expense").isEqualTo(422);
        assertThat(tx(p, "INCOME", a, "1.00", "2026-03-01", exp).status()).isEqualTo(422);
        assertThat(tx(p, "EXPENSE", UUID.randomUUID().toString(), "1.00", "2026-03-01", null).status()).isEqualTo(422);
        Resp wrongCurrency = post(p, "/transactions", Map.of("kind", "EXPENSE", "account_id", a, "amount", "1.00", "currency", "USD", "occurred_on", "2026-03-01"));
        assertThat(wrongCurrency.status()).isEqualTo(422);
        assertThat(wrongCurrency.raw().body()).doesNotContain("Exception");
        assertThat(post(p, "/transactions", Map.of("kind", "EXPENSE", "account_id", a, "amount", "1.00", "occurred_on", "2026-03-01",
                "tags", List.of("ok", "bad tag!"))).status()).isEqualTo(422);
        assertThat(post(p, "/transactions", Map.of("kind", "EXPENSE", "account_id", a, "amount", "1.00", "occurred_on", "2026-03-01",
                "note", "x".repeat(1001))).status()).isEqualTo(422);
    }

    @Test
    void transfersAreNotIncomeOrExpenseAndLeaveNetWorthUnchanged() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "1000.00");
        String b = account(p, "Savings", "BANK", "INR", "0", "2026-01-01");
        JsonNode before = get(p, "/net-worth").body().path("currencies").get(0);
        Resp t = post(p, "/transfers", Map.of("from_account_id", a, "to_account_id", b, "amount", "400.00", "occurred_on", "2026-03-05", "note", "Move to savings"));
        assertThat(t.status()).isEqualTo(201);
        assertThat(t.text("kind")).isEqualTo("TRANSFER");
        assertThat(balance(p, a)).isEqualTo("600.00");
        assertThat(balance(p, b)).isEqualTo("400.00");
        JsonNode after = get(p, "/net-worth").body().path("currencies").get(0);
        assertThat(after.path("net_worth").asString()).isEqualTo(before.path("net_worth").asString()).isEqualTo("1000.00");
        JsonNode cf = get(p, "/summaries/cashflow?from=2026-03-01&to=2026-03-31").body().path("currencies");
        assertThat(cf.toString()).as("a transfer produces no income or expense").doesNotContain("400.00");
        assertThat(get(p, "/transactions?kind=TRANSFER").body().path("items")).hasSize(1);
    }

    @Test
    void aTransferFeeReducesNetWorthButIsNotAnExpense() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "1000.00");
        String b = account(p, "Wallet", "WALLET", "INR", "0", "2026-01-01");
        ok(post(p, "/transfers", Map.of("from_account_id", a, "to_account_id", b, "amount", "100.00", "to_amount", "99.50", "occurred_on", "2026-03-05")));
        assertThat(get(p, "/net-worth").body().path("currencies").get(0).path("net_worth").asString()).isEqualTo("999.50");
        JsonNode cf = get(p, "/summaries/cashflow?from=2026-03-01&to=2026-03-31").body().path("currencies").get(0);
        assertThat(cf.path("transfer_fees").asString()).isEqualTo("0.50");
        assertThat(cf.path("gross_expense").asString()).isEqualTo("0.00");
        assertThat(post(p, "/transfers", Map.of("from_account_id", a, "to_account_id", b, "amount", "10.00", "to_amount", "10.01", "occurred_on", "2026-03-05")).status())
                .as("the destination cannot receive more than was sent in the same currency").isEqualTo(422);
    }

    @Test
    void crossCurrencyTransfersNeedBothAmountsAndInventNoRate() throws Exception {
        Person p = person();
        String inr = bank(p, "India", "10000.00");
        String usd = account(p, "US", "BANK", "USD", "0", "2026-01-01");
        assertThat(post(p, "/transfers", Map.of("from_account_id", inr, "to_account_id", usd, "amount", "8300.00", "occurred_on", "2026-03-05")).status()).isEqualTo(422);
        Resp t = post(p, "/transfers", Map.of("from_account_id", inr, "to_account_id", usd, "amount", "8300.00", "to_amount", "100.00", "occurred_on", "2026-03-05"));
        assertThat(t.status()).isEqualTo(201);
        assertThat(t.text("currency")).isEqualTo("INR");
        assertThat(t.text("to_currency")).isEqualTo("USD");
        assertThat(balance(p, inr)).isEqualTo("1700.00");
        assertThat(balance(p, usd)).isEqualTo("100.00");
        JsonNode worth = get(p, "/net-worth").body().path("currencies");
        assertThat(worth).as("currencies are reported separately, never mixed").hasSize(2);
        assertThat(post(p, "/transfers", Map.of("from_account_id", inr, "to_account_id", usd, "amount", "10.00", "to_amount", "0.123", "occurred_on", "2026-03-05")).status())
                .as("USD has two decimals").isEqualTo(422);
    }

    @Test
    void transferInputIsValidated() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "100.00");
        String b = account(p, "Other", "BANK", "INR", "0", "2026-01-01");
        assertThat(post(p, "/transfers", Map.of("from_account_id", a, "to_account_id", a, "amount", "1.00", "occurred_on", "2026-03-05")).status()).isEqualTo(422);
        assertThat(post(p, "/transfers", Map.of("from_account_id", a, "to_account_id", UUID.randomUUID().toString(), "amount", "1.00", "occurred_on", "2026-03-05")).status()).isEqualTo(422);
        assertThat(post(p, "/transfers", Map.of("from_account_id", a, "to_account_id", b, "amount", "0", "occurred_on", "2026-03-05")).status()).isEqualTo(422);
        assertThat(post(p, "/transfers", Map.of("from_account_id", a, "to_account_id", b, "amount", "1.00", "occurred_on", "nonsense")).status()).isEqualTo(422);
    }

    @Test
    void refundsReduceExpensesAreNotIncomeAndCannotExceedTheOriginal() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "1000.00");
        String food = category(p, "Food", "EXPENSE");
        String exp = ok(tx(p, "EXPENSE", a, "100.00", "2026-03-02", food));
        Resp r1 = post(p, "/transactions", Map.of("kind", "REFUND", "account_id", a, "amount", "30.00", "occurred_on", "2026-03-04", "refund_of", exp));
        assertThat(r1.status()).as(r1.raw().body()).isEqualTo(201);
        assertThat(r1.text("category_id")).as("the refund inherits the expense's category").isEqualTo(food);
        assertThat(balance(p, a)).isEqualTo("930.00");
        JsonNode cf = get(p, "/summaries/cashflow?from=2026-03-01&to=2026-03-31").body().path("currencies").get(0);
        assertThat(cf.path("income").asString()).isEqualTo("0.00");
        assertThat(cf.path("gross_expense").asString()).isEqualTo("100.00");
        assertThat(cf.path("refunds").asString()).isEqualTo("30.00");
        assertThat(cf.path("net_expense").asString()).isEqualTo("70.00");
        assertThat(cf.path("categories").get(0).path("expense").asString()).isEqualTo("70.00");
        Resp tooMuch = post(p, "/transactions", Map.of("kind", "REFUND", "account_id", a, "amount", "70.01", "occurred_on", "2026-03-05", "refund_of", exp));
        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(tooMuch.body().path("detail").asString()).contains("70.00 left to refund");
        ok(post(p, "/transactions", Map.of("kind", "REFUND", "account_id", a, "amount", "70.00", "occurred_on", "2026-03-05", "refund_of", exp)));
        assertThat(post(p, "/transactions/" + exp + "/void", Map.of("reason", "oops")).status()).as("refunds must be voided first").isEqualTo(409);
        assertThat(patch(p, "/transactions/" + exp, Map.of("amount", "50.00", "version", 0)).status()).as("cannot drop below refunded total").isEqualTo(422);
        assertThat(post(p, "/transactions", Map.of("kind", "REFUND", "account_id", a, "amount", "1.00", "occurred_on", "2026-03-05", "refund_of", UUID.randomUUID().toString())).status()).isEqualTo(422);
    }

    @Test
    void concurrentRefundsNeverExceedTheOriginalExpense() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "1000.00");
        String exp = ok(tx(p, "EXPENSE", a, "100.00", "2026-03-02", null));
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            calls.add(() -> post(p, "/transactions", Map.of("kind", "REFUND", "account_id", a, "amount", "40.00", "occurred_on", "2026-03-05", "refund_of", exp)).status());
        }
        List<Integer> statuses = concurrently(calls);
        assertThat(statuses.stream().filter(s -> s == 201).count()).as("only two 40.00 refunds fit in 100.00").isEqualTo(2);
        assertThat(count("select coalesce(sum(amount), 0) from transactions where refund_of = :p0", UUID.fromString(exp))).isLessThanOrEqualTo(100L);
    }

    // ---- idempotency ----

    @Test
    void aRetriedCreateReturnsTheFirstResultAndNeverDuplicates() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "0");
        Map<String, Object> body = Map.of("kind", "EXPENSE", "account_id", a, "amount", "42.00", "occurred_on", "2026-03-05");
        Resp first = postKeyed(p, "/transactions", body, "key-1");
        Resp retry = postKeyed(p, "/transactions", body, "key-1");
        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(retry.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(first.header("Idempotency-Replayed")).isNull();
        assertThat(count("select count(*) from transactions where tenant_id = :p0", p.space())).isEqualTo(1L);
        assertThat(balance(p, a)).isEqualTo("-42.00");
    }

    @Test
    void reusingAKeyForADifferentRequestIsRefusedAndAMissingKeyIsRequired() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "0");
        postKeyed(p, "/transactions", Map.of("kind", "EXPENSE", "account_id", a, "amount", "1.00", "occurred_on", "2026-03-05"), "k");
        Resp reused = postKeyed(p, "/transactions", Map.of("kind", "EXPENSE", "account_id", a, "amount", "2.00", "occurred_on", "2026-03-05"), "k");
        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        Resp missing = postNoKey(p, "/transactions", Map.of("kind", "EXPENSE", "account_id", a, "amount", "1.00", "occurred_on", "2026-03-05"));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(postNoKey(p, "/transfers", Map.of("from_account_id", a, "to_account_id", a, "amount", "1")).status()).isEqualTo(400);
        assertThat(count("select count(*) from transactions where tenant_id = :p0", p.space())).isEqualTo(1L);
    }

    @Test
    void aFailedRequestDoesNotConsumeItsKeySoACorrectedRetryWorks() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "0");
        Resp bad = postKeyed(p, "/transactions", Map.of("kind", "EXPENSE", "account_id", a, "amount", "-1", "occurred_on", "2026-03-05"), "retry-me");
        assertThat(bad.status()).isEqualTo(422);
        Resp fixed = postKeyed(p, "/transactions", Map.of("kind", "EXPENSE", "account_id", a, "amount", "1.00", "occurred_on", "2026-03-05"), "retry-me");
        assertThat(fixed.status()).as("a validation failure leaves no key behind, so the retry with the same key runs").isEqualTo(201);
    }

    @Test
    void concurrentIdenticalRequestsCreateExactlyOneTransaction() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "0");
        Map<String, Object> body = Map.of("kind", "EXPENSE", "account_id", a, "amount", "9.99", "occurred_on", "2026-03-05");
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> postKeyed(p, "/transactions", body, "same-key").status());
        }
        assertThat(concurrently(calls)).containsOnly(201);
        assertThat(count("select count(*) from transactions where tenant_id = :p0", p.space())).isEqualTo(1L);
        assertThat(balance(p, a)).isEqualTo("-9.99");
    }

    @Test
    void keysAreScopedToTheProfile() throws Exception {
        Person p = person();
        Person q = person();
        String a = bank(p, "Main", "0");
        String b = bank(q, "Main", "0");
        Resp r1 = postKeyed(p, "/transactions", Map.of("kind", "EXPENSE", "account_id", a, "amount", "1.00", "occurred_on", "2026-03-05"), "shared-key");
        Resp r2 = postKeyed(q, "/transactions", Map.of("kind", "EXPENSE", "account_id", b, "amount", "1.00", "occurred_on", "2026-03-05"), "shared-key");
        assertThat(r1.status()).isEqualTo(201);
        assertThat(r2.status()).isEqualTo(201);
        assertThat(r2.id()).isNotEqualTo(r1.id());
    }

    // ---- editing, voiding ----

    @Test
    void editingChangesBalancesKeepsAuditHistoryAndDetectsConflicts() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "100.00");
        String food = category(p, "Food", "EXPENSE");
        String id = ok(tx(p, "EXPENSE", a, "10.00", "2026-03-02", null));
        Resp edited = patch(p, "/transactions/" + id, Map.of("amount", "12.50", "category_id", food, "note", "Lunch", "payee", "Cafe", "tags", List.of("work", "Lunch"), "version", 0));
        assertThat(edited.status()).as(edited.raw().body()).isEqualTo(200);
        assertThat(edited.text("amount")).isEqualTo("12.50");
        assertThat(edited.body().path("tags").toString()).isEqualTo("[\"lunch\",\"work\"]");
        assertThat(edited.body().path("version").asInt()).isEqualTo(1);
        assertThat(balance(p, a)).isEqualTo("87.50");
        assertThat(patch(p, "/transactions/" + id, Map.of("amount", "1.00", "version", 0)).status()).as("stale version").isEqualTo(409);
        assertThat(patch(p, "/transactions/" + id, Map.of("amount", "1.00")).status()).isEqualTo(422);
        assertThat(patch(p, "/transactions/" + id, Map.of("amount", "1.001", "version", 1)).status()).isEqualTo(422);
        assertThat(patch(p, "/transactions/" + id, Map.of("uncategorized", true, "tags", List.of(), "version", 1)).status()).isEqualTo(200);
        String detail = jdbc.sql("select detail from finance_audit_events where tenant_id = :t and event_type = 'TRANSACTION_EDITED' and detail like 'before [10.00%' ")
                .param("t", p.space()).query(String.class).single();
        assertThat(detail).contains("10.00").contains("12.50");
    }

    @Test
    void voidedTransactionsDisappearFromTotalsButStayInTheHistoryAndCanBeRestored() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "100.00");
        String id = ok(tx(p, "EXPENSE", a, "40.00", "2026-03-02", null));
        assertThat(balance(p, a)).isEqualTo("60.00");
        assertThat(post(p, "/transactions/" + id + "/void", Map.of()).status()).as("a reason is required").isEqualTo(422);
        Resp voided = post(p, "/transactions/" + id + "/void", Map.of("reason", "Entered twice"));
        assertThat(voided.status()).isEqualTo(200);
        assertThat(voided.text("status")).isEqualTo("VOIDED");
        assertThat(balance(p, a)).isEqualTo("100.00");
        assertThat(get(p, "/transactions").body().path("items")).isEmpty();
        assertThat(get(p, "/transactions?include_voided=true").body().path("items")).hasSize(1);
        assertThat(patch(p, "/transactions/" + id, Map.of("amount", "1.00", "version", 1)).status()).isEqualTo(409);
        assertThat(post(p, "/transactions/" + id + "/void", Map.of("reason", "again")).status()).isEqualTo(409);
        assertThat(post(p, "/transactions/" + id + "/restore", Map.of()).text("status")).isEqualTo("POSTED");
        assertThat(balance(p, a)).isEqualTo("60.00");
        assertThat(get(p, "/transactions/" + id).status()).isEqualTo(200);
        assertThat(count("select count(*) from transactions where id = :p0", UUID.fromString(id))).as("nothing is silently deleted").isEqualTo(1L);
    }

    // ---- dates and time zones ----

    @Test
    void todayAndFutureDatedTransactionsFollowTheUsersTimeZone() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "100.00");
        clock.set("2026-03-31T19:00:00Z"); // 2026-04-01 00:30 in India, still 2026-03-31 in UTC
        assertThat(get(p, "/finance-settings").text("today")).isEqualTo("2026-03-31");
        ok(tx(p, "EXPENSE", a, "10.00", "2026-04-01", null));
        assertThat(balance(p, a)).as("dated tomorrow in UTC: not yet part of today's balance").isEqualTo("100.00");
        assertThat(put(p, "/finance-settings", Map.of("timezone", "Asia/Kolkata")).text("today")).isEqualTo("2026-04-01");
        assertThat(balance(p, a)).as("the same transaction is today's in India").isEqualTo("90.00");
        assertThat(get(p, "/net-worth").body().path("as_of").asString()).isEqualTo("2026-04-01");
        assertThat(put(p, "/finance-settings", Map.of("timezone", "Not/AZone")).status()).isEqualTo(422);
        assertThat(put(p, "/finance-settings", Map.of("timezone", "Pacific/Auckland")).text("today")).isEqualTo("2026-04-01");
        clock.set("2026-03-31T10:59:00Z");
        assertThat(get(p, "/finance-settings").text("today")).isEqualTo("2026-03-31");
        clock.set("2026-03-31T11:00:00Z");
        assertThat(get(p, "/finance-settings").text("today")).as("midnight in Auckland (UTC+13)").isEqualTo("2026-04-01");
    }

    @Test
    void netWorthOnAPastDateIsReproducibleAfterLaterActivity() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "1000.00");
        ok(tx(p, "EXPENSE", a, "100.00", "2026-02-10", null));
        String before = get(p, "/net-worth?as_of=2026-02-28").body().path("currencies").get(0).path("net_worth").asString();
        ok(tx(p, "EXPENSE", a, "500.00", "2026-03-10", null));
        ok(tx(p, "INCOME", a, "70.00", "2026-02-20", null));
        assertThat(get(p, "/net-worth?as_of=2026-02-28").body().path("currencies").get(0).path("net_worth").asString()).isEqualTo("970.00");
        assertThat(before).isEqualTo("900.00");
        assertThat(get(p, "/net-worth?as_of=2026-12-31").status()).as("not in the future").isEqualTo(422);
        assertThat(get(p, "/net-worth?as_of=2025-12-31").body().path("currencies")).as("the account did not exist yet").isEmpty();
    }

    // ---- listing, filtering, pagination ----

    @Test
    void listingIsPaginatedStablyAndFiltersAreCombinable() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "0");
        String food = category(p, "Food", "EXPENSE");
        for (int i = 1; i <= 25; i++) {
            ok(tx(p, "EXPENSE", a, i + ".00", "2026-03-" + String.format("%02d", (i % 5) + 1), i % 2 == 0 ? food : null, i % 3 == 0 ? new String[] {"fun"} : new String[] {}));
        }
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        String lastDate = "9999";
        do {
            Resp page = get(p, "/transactions?limit=10" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.status()).isEqualTo(200);
            for (JsonNode item : page.body().path("items")) {
                assertThat(seen.add(item.path("id").asString())).as("no duplicates across pages").isTrue();
                String date = item.path("occurred_on").asString();
                assertThat(date.compareTo(lastDate)).as("newest first").isLessThanOrEqualTo(0);
                lastDate = date;
            }
            cursor = page.body().path("next_cursor").isNull() ? null : page.body().path("next_cursor").asString();
            pages++;
        } while (cursor != null);
        assertThat(seen).hasSize(25);
        assertThat(pages).isEqualTo(3);
        assertThat(get(p, "/transactions?category_id=" + food).body().path("items")).hasSize(12);
        assertThat(get(p, "/transactions?tags=fun").body().path("items")).hasSize(8);
        assertThat(get(p, "/transactions?from=2026-03-02&to=2026-03-02").body().path("items").size()).as("range ends are inclusive").isGreaterThan(0);
        assertThat(get(p, "/transactions?from=2026-03-05&to=2026-03-01").status()).isEqualTo(422);
        assertThat(get(p, "/transactions?limit=0").status()).isEqualTo(422);
        assertThat(get(p, "/transactions?limit=201").status()).isEqualTo(422);
        assertThat(get(p, "/transactions?cursor=garbage").status()).isEqualTo(422);
        assertThat(get(p, "/transactions?kind=NOPE").status()).isEqualTo(422);
        assertThat(get(p, "/transactions?account_id=" + UUID.randomUUID()).body().path("items")).isEmpty();
    }

    @Test
    void tagFiltersSupportAllAndAnyAndSearchTreatsWildcardsAsText() throws Exception {
        Person p = person();
        String a = bank(p, "Main", "0");
        ok(tx(p, "EXPENSE", a, "1.00", "2026-03-01", null, "a", "b"));
        ok(tx(p, "EXPENSE", a, "2.00", "2026-03-02", null, "a"));
        ok(tx(p, "EXPENSE", a, "3.00", "2026-03-03", null, "b"));
        assertThat(get(p, "/transactions?tags=a,b&tag_mode=all").body().path("items")).hasSize(1);
        assertThat(get(p, "/transactions?tags=a,b&tag_mode=any").body().path("items")).hasSize(3);
        assertThat(get(p, "/transactions?tags=a").body().path("items")).hasSize(2);
        ok(post(p, "/transactions", Map.of("kind", "EXPENSE", "account_id", a, "amount", "4.00", "occurred_on", "2026-03-04", "note", "50% off sale_item")));
        ok(post(p, "/transactions", Map.of("kind", "EXPENSE", "account_id", a, "amount", "5.00", "occurred_on", "2026-03-04", "note", "plain")));
        assertThat(get(p, "/transactions?q=" + "50%25").body().path("items")).as("% is literal text").hasSize(1);
        assertThat(get(p, "/transactions?q=_").body().path("items")).as("_ is literal text").hasSize(1);
        assertThat(get(p, "/transactions?q=SALE").body().path("items")).as("case-insensitive").hasSize(1);
        Resp tags = get(p, "/tags");
        assertThat(tags.body().path("items")).hasSize(2);
        String tagId = tags.body().path("items").get(0).path("id").asString();
        assertThat(patch(p, "/tags/" + tagId, Map.of("name", "Renamed")).text("name")).isEqualTo("renamed");
        assertThat(get(p, "/transactions?tags=renamed").body().path("items").size()).as("rename updates every transaction").isGreaterThan(0);
        assertThat(patch(p, "/tags/" + tagId, Map.of("name", "b")).status()).isIn(200, 409);
    }

    // ---- categories ----

    @Test
    void categoriesSupportSubcategoriesMergeAndStarterSets() throws Exception {
        Person p = person();
        Resp starter = raw("POST", base(p) + "/categories/starter", token(p), null);
        assertThat(starter.status()).isEqualTo(200);
        int created = starter.body().path("items").size();
        assertThat(created).isEqualTo(16);
        assertThat(raw("POST", base(p) + "/categories/starter", token(p), null).body().path("items")).as("idempotent").hasSize(created);
        String groceries = null;
        for (JsonNode c : starter.body().path("items")) {
            if (c.path("name").asString().equals("Groceries")) {
                groceries = c.path("id").asString();
            }
        }
        Resp child = raw("POST", base(p) + "/categories", token(p), Map.of("name", "Vegetables", "kind", "EXPENSE", "parent_id", groceries));
        assertThat(child.status()).isEqualTo(201);
        assertThat(raw("POST", base(p) + "/categories", token(p), Map.of("name", "Deeper", "kind", "EXPENSE", "parent_id", child.id())).status()).isEqualTo(422);
        assertThat(raw("POST", base(p) + "/categories", token(p), Map.of("name", "Vegetables", "kind", "EXPENSE", "parent_id", groceries)).status()).isEqualTo(409);
        assertThat(raw("POST", base(p) + "/categories", token(p), Map.of("name", "Bad", "kind", "INCOME", "parent_id", groceries)).status()).isEqualTo(422);
        String a = bank(p, "Main", "0");
        ok(tx(p, "EXPENSE", a, "10.00", "2026-03-01", child.id()));
        ok(tx(p, "EXPENSE", a, "20.00", "2026-03-02", groceries));
        assertThat(get(p, "/transactions?category_id=" + groceries).body().path("items")).as("a parent includes its subcategories").hasSize(2);
        assertThat(raw("POST", base(p) + "/categories/" + groceries + "/archive", token(p), null).status()).as("subcategories first").isEqualTo(409);
        String other = category(p, "Pantry", "EXPENSE");
        assertThat(raw("POST", base(p) + "/categories/" + groceries + "/merge", token(p), Map.of("into_category_id", other)).status()).as("merge subcategories first").isEqualTo(409);
        assertThat(raw("POST", base(p) + "/categories/" + child.id() + "/merge", token(p), Map.of("into_category_id", other)).status()).isEqualTo(200);
        assertThat(get(p, "/transactions?category_id=" + other).body().path("items")).hasSize(1);
        assertThat(count("select count(*) from transactions where category_id = :p0", UUID.fromString(child.id()))).as("no orphans").isZero();
        assertThat(raw("POST", base(p) + "/categories/" + other + "/merge", token(p), Map.of("into_category_id", other)).status()).isEqualTo(422);
        assertThat(raw("GET", base(p) + "/categories?include_archived=true", token(p), null).body().path("items").size()).isEqualTo(created + 2);
    }
}
