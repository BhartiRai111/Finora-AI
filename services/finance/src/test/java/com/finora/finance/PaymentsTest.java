package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/** Payments: partial, full, excess, duplicates, allocation limits, reversal, concurrency, rollback. */
class PaymentsTest extends BusinessTestSupport {

    @Test
    void partialPaymentsReduceTheOutstandingBalanceExactlyUntilPaid() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String inv = issued(b, c, "100.00");
        assertThat(status(b, inv)).isEqualTo("UNPAID");

        Resp p1 = pay(b, c, "33.33", "UTR-1", List.of(alloc(inv, "33.33")));
        assertThat(p1.status()).as(p1.raw().body()).isEqualTo(201);
        assertThat(p1.text("allocated")).isEqualTo("33.33");
        assertThat(p1.text("unallocated")).isEqualTo("0.00");
        Resp mid = bget(b, "/invoices/" + inv);
        assertThat(mid.text("display_status")).isEqualTo("PARTIALLY_PAID");
        assertThat(mid.text("paid")).isEqualTo("33.33");
        assertThat(mid.text("outstanding")).isEqualTo("66.67");

        ok(pay(b, c, "66.67", "UTR-2", List.of(alloc(inv, "66.67"))));
        Resp done = bget(b, "/invoices/" + inv);
        assertThat(done.text("display_status")).isEqualTo("PAID");
        assertThat(done.text("outstanding")).isEqualTo("0.00");
        assertThat(done.body().path("payments")).hasSize(2);
        assertThat(bget(b, "/invoices?status=PAID").body().path("total").asLong()).isEqualTo(1);
        assertThat(bget(b, "/invoices?status=PARTIALLY_PAID").body().path("total").asLong()).isZero();
    }

    @Test
    void anAllocationAboveTheOutstandingAmountIsRefusedAndNothingIsStored() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String inv = issued(b, c, "100.00");
        Resp r = pay(b, c, "150.00", "UTR-X", List.of(alloc(inv, "100.01")));
        assertCode(r, 422, "ALLOCATION_EXCEEDS");
        assertThat(count("select count(*) from payments where tenant_id = :p0", b.space())).isZero();
        assertThat(outstanding(b, inv)).isEqualTo("100.00");
        // exactly the outstanding amount is accepted, and the extra stays on the payment as unallocated credit
        Resp ok = pay(b, c, "150.00", "UTR-X", List.of(alloc(inv, "100.00")));
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.text("allocated")).isEqualTo("100.00");
        assertThat(ok.text("unallocated")).isEqualTo("50.00");
        assertThat(status(b, inv)).isEqualTo("PAID");
        assertThat(bget(b, "/clients/" + c).body().path("unallocated_payments").get(0).path("amount").asString()).isEqualTo("50.00");
    }

    @Test
    void allocationsCannotExceedThePaymentAndEveryRuleFailsAtomically() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String i1 = issued(b, c, "100.00");
        String i2 = issued(b, c, "100.00");
        assertCode(pay(b, c, "150.00", "A", List.of(alloc(i1, "100.00"), alloc(i2, "60.00"))), 422, "ALLOCATION_EXCEEDS");
        // second allocation is bad: the first one must not survive
        assertCode(pay(b, c, "500.00", "B", List.of(alloc(i1, "40.00"), alloc(i2, "100.01"))), 422, "ALLOCATION_EXCEEDS");
        assertThat(count("select count(*) from payments where tenant_id = :p0", b.space())).isZero();
        assertThat(count("select count(*) from payment_allocations where tenant_id = :p0", b.space())).isZero();
        assertThat(outstanding(b, i1)).isEqualTo("100.00");
        assertThat(pay(b, c, "10.00", "C", List.of(alloc(i1, "5.00"), alloc(i1, "5.00"))).status()).as("duplicate invoice in one request").isEqualTo(422);
        assertThat(pay(b, c, "10.00", "D", List.of(alloc(i1, "0.00"))).status()).isEqualTo(422);
        assertThat(pay(b, c, "10.00", "E", List.of(alloc(UUID.randomUUID().toString(), "5.00"))).status()).isEqualTo(422);
    }

    @Test
    void unallocatedMoneyCanBeAppliedLaterButNeverTwice() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String inv = issued(b, c, "80.00");
        String pay = ok(pay(b, c, "100.00", "ADV-1", null));
        Resp noAlloc = bget(b, "/payments/" + pay);
        assertThat(noAlloc.text("unallocated")).isEqualTo("100.00");
        assertThat(status(b, inv)).isEqualTo("UNPAID");

        Resp r = bpost(b, "/payments/" + pay + "/allocations", Map.of("allocations", List.of(alloc(inv, "80.00"))));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.text("unallocated")).isEqualTo("20.00");
        assertThat(status(b, inv)).isEqualTo("PAID");
        String inv2 = issued(b, c, "50.00");
        assertCode(bpost(b, "/payments/" + pay + "/allocations", Map.of("allocations", List.of(alloc(inv2, "20.01")))), 422, "ALLOCATION_EXCEEDS");
        assertThat(bpost(b, "/payments/" + pay + "/allocations", Map.of("allocations", List.of(alloc(inv2, "20.00")))).status()).isEqualTo(200);
        assertThat(outstanding(b, inv2)).isEqualTo("30.00");
        assertThat(bpost(b, "/payments/" + pay + "/allocations", Map.of("allocations", List.of())).status()).isEqualTo(422);
    }

    @Test
    void autoAllocationPaysTheOldestDueFirstAndKeepsTheRemainder() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String later = ok(draft(b, c, "2026-03-01", "2026-04-30", "INR", List.of(line("A", "1", "60.00")), null));
        String sooner = ok(draft(b, c, "2026-03-01", "2026-03-20", "INR", List.of(line("A", "1", "60.00")), null));
        ok(issue(b, later, "60.00"));
        ok(issue(b, sooner, "60.00"));
        Resp r = bpost(b, "/payments", Map.of("client_id", c, "payment_date", "2026-03-10", "amount", "100.00", "currency", "INR", "method", "UPI", "auto_allocate", true));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        assertThat(outstanding(b, sooner)).isEqualTo("0.00");
        assertThat(outstanding(b, later)).isEqualTo("20.00");
        assertThat(r.text("allocated")).isEqualTo("100.00");
        assertThat(bpost(b, "/payments", Map.of("client_id", c, "payment_date", "2026-03-10", "amount", "10.00", "currency", "INR", "method", "UPI",
                "auto_allocate", true, "allocations", List.of(alloc(later, "1.00")))).status()).as("both modes at once").isEqualTo(422);
    }

    @Test
    void theSameRetryNeverRecordsTheMoneyTwice() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String inv = issued(b, c, "100.00");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("client_id", c, "payment_date", "2026-03-10", "amount", "40.00", "currency", "INR", "method", "CASH",
                "allocations", List.of(alloc(inv, "40.00")));
        Resp first = bpostKeyed(b, "/payments", body, key);
        Resp retry = bpostKeyed(b, "/payments", body, key);
        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(outstanding(b, inv)).isEqualTo("60.00");
        assertThat(count("select count(*) from payments where tenant_id = :p0", b.space())).isEqualTo(1);
        // a different body under the same key is refused, and a key is mandatory
        assertThat(bpostKeyed(b, "/payments", Map.of("client_id", c, "payment_date", "2026-03-10", "amount", "41.00", "currency", "INR", "method", "CASH"), key).status()).isEqualTo(422);
        assertThat(as(b, b.owner(), "OWNER", "POST", "/payments", body).status()).isEqualTo(400);
    }

    @Test
    void theSameClientCannotUseOneReferenceTwiceButAVoidFreesIt() throws Exception {
        Biz b = biz();
        String c1 = client(b, "One");
        String c2 = client(b, "Two");
        String first = ok(pay(b, c1, "10.00", "UTR-77", null));
        assertCode(pay(b, c1, "10.00", "utr-77", null), 409, "CONFLICT");
        assertThat(pay(b, c2, "10.00", "UTR-77", null).status()).as("another client may reuse a reference").isEqualTo(201);
        ok(bpost(b, "/payments/" + first + "/void", Map.of("reason", "Entered twice")));
        assertThat(pay(b, c1, "10.00", "UTR-77", null).status()).isEqualTo(201);
        assertThat(count("select count(*) from payments where tenant_id = :p0 and client_id = :p1 and status = 'RECORDED'", b.space(), UUID.fromString(c1))).isEqualTo(1);
    }

    @Test
    void concurrentPaymentsCannotOverpayAnInvoice() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String inv = issued(b, c, "100.00");
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int n = i;
            calls.add(() -> pay(b, c, "30.00", "R" + n, List.of(alloc(inv, "30.00"))).status());
        }
        List<Integer> statuses = concurrently(calls);
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(3);
        assertThat(statuses.stream().filter(s -> s == 422)).hasSize(3);
        Resp i = bget(b, "/invoices/" + inv);
        assertThat(i.text("paid")).isEqualTo("90.00");
        assertThat(i.text("outstanding")).isEqualTo("10.00");
        assertThat(count("select count(*) from payments where tenant_id = :p0", b.space())).as("refused payments leave no rows").isEqualTo(3);
    }

    @Test
    void voidingAPaymentRestoresTheBalanceAndCancellingNeedsThePaymentsGone() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String inv = issued(b, c, "100.00");
        String pay = ok(pay(b, c, "100.00", "UTR-9", List.of(alloc(inv, "100.00"))));
        assertThat(status(b, inv)).isEqualTo("PAID");
        assertCode(bpost(b, "/invoices/" + inv + "/cancel", Map.of("confirm", true, "reason", "oops")), 409, "ALLOCATION_EXISTS");

        assertCode(bpost(b, "/payments/" + pay + "/void", Map.of()), 422, "VALIDATION_FAILED"); // reason required
        Resp voided = bpost(b, "/payments/" + pay + "/void", Map.of("reason", "Cheque bounced"));
        assertThat(voided.status()).as(voided.raw().body()).isEqualTo(200);
        assertThat(voided.text("status")).isEqualTo("VOIDED");
        assertThat(voided.text("allocated")).isEqualTo("0.00");
        assertThat(voided.text("unallocated")).isEqualTo("0.00");
        Resp restored = bget(b, "/invoices/" + inv);
        assertThat(restored.text("outstanding")).isEqualTo("100.00");
        assertThat(restored.text("display_status")).isEqualTo("UNPAID");
        assertThat(restored.body().path("payments").get(0).path("payment_status").asString()).as("history still shows the voided payment").isEqualTo("VOIDED");
        assertCode(bpost(b, "/payments/" + pay + "/void", Map.of("reason", "again")), 409, "INVALID_STATE_TRANSITION");
        assertCode(bpost(b, "/payments/" + pay + "/allocations", Map.of("allocations", List.of(alloc(inv, "1.00")))), 409, "INVALID_STATE_TRANSITION");

        Resp cancelled = bpost(b, "/invoices/" + inv + "/cancel", Map.of("confirm", true, "reason", "Wrong client"));
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(bget(b, "/payments?include_voided=true").body().path("total").asLong()).isEqualTo(1);
        assertThat(bget(b, "/payments").body().path("total").asLong()).isZero();
    }

    @Test
    void paymentsOnlyApplyToIssuedInvoicesOfTheSameClientAndCurrency() throws Exception {
        Biz b = biz();
        String c1 = client(b, "One");
        String c2 = client(b, "Two");
        String draftInv = draftInvoice(b, c1, "10.00");
        String other = issued(b, c2, "10.00");
        String cancelled = issued(b, c1, "10.00");
        ok(bpost(b, "/invoices/" + cancelled + "/cancel", Map.of("confirm", true, "reason", "no")));
        assertCode(pay(b, c1, "5.00", "a", List.of(alloc(draftInv, "5.00"))), 409, "CONFLICT");
        assertThat(pay(b, c1, "5.00", "b", List.of(alloc(other, "5.00"))).status()).as("another client's invoice").isEqualTo(422);
        assertCode(pay(b, c1, "5.00", "c", List.of(alloc(cancelled, "5.00"))), 409, "CONFLICT");
        String usd = ok(draft(b, c1, "2026-03-01", null, "USD", List.of(line("A", "1", "10.00")), null));
        ok(issue(b, usd, "10.00"));
        assertThat(pay(b, c1, "5.00", "d", List.of(alloc(usd, "5.00"))).status()).as("currency mismatch, nothing converted").isEqualTo(422);
        assertThat(count("select count(*) from payments where tenant_id = :p0", b.space())).isZero();
    }

    @Test
    void amountsDatesAndMethodsAreValidated() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        Map<String, Object> base = Map.of("client_id", c, "payment_date", "2026-03-10", "amount", "10.00", "currency", "INR", "method", "CASH");
        assertThat(bpost(b, "/payments", base).status()).isEqualTo(201);
        for (Map<String, Object> bad : List.of(
                override(base, "amount", "0"), override(base, "amount", "10.001"), override(base, "amount", "-5.00"), override(base, "amount", "1e2"),
                override(base, "currency", "JPYY"), override(base, "method", "BARTER"), override(base, "payment_date", "2026-03-16"),
                override(base, "payment_date", "10/03/2026"), override(base, "client_id", UUID.randomUUID().toString()))) {
            Resp r = bpost(b, "/payments", bad);
            assertThat(r.status()).as(bad + " -> " + r.raw().body()).isEqualTo(422);
        }
        // a JPY payment must be whole yen
        assertThat(bpost(b, "/payments", override(override(base, "currency", "JPY"), "amount", "10.5")).status()).isEqualTo(422);
        // today in the business time zone is allowed (2026-03-15 at 10:00Z is 15:30 in Kolkata)
        assertThat(bpost(b, "/payments", override(base, "payment_date", "2026-03-15")).status()).isEqualTo(201);
        clock.set("2026-03-15T20:00:00Z"); // now the 16th in Kolkata
        assertThat(bpost(b, "/payments", override(base, "payment_date", "2026-03-16")).status()).isEqualTo(201);
    }

    private static Map<String, Object> override(Map<String, Object> base, String key, Object value) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(base);
        m.put(key, value);
        return m;
    }

    @Test
    void listsFilterAndPage() throws Exception {
        Biz b = biz();
        String c1 = client(b, "Alpha Co");
        String c2 = client(b, "Beta Co");
        for (int i = 0; i < 5; i++) {
            ok(pay(b, i % 2 == 0 ? c1 : c2, "10.00", "P" + i, null));
        }
        Resp page = bget(b, "/payments?limit=2&offset=0");
        assertThat(page.body().path("total").asLong()).isEqualTo(5);
        assertThat(page.body().path("items")).hasSize(2);
        assertThat(bget(b, "/payments?client_id=" + c2).body().path("total").asLong()).isEqualTo(2);
        assertThat(bget(b, "/payments?q=p3").body().path("total").asLong()).isEqualTo(1);
        assertThat(bget(b, "/payments?method=CASH").body().path("total").asLong()).isZero();
        assertThat(bget(b, "/payments?method=BANK_TRANSFER").body().path("total").asLong()).isEqualTo(5);
        assertThat(bget(b, "/payments?method=WHAT").status()).isEqualTo(422);
        assertThat(bget(b, "/payments?from=2026-03-11").body().path("total").asLong()).isZero();
    }
}
