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

/** Invoices: drafting, totals, numbering, lifecycle, derived status, due-date boundaries, validation, concurrency. */
class InvoicesTest extends BusinessTestSupport {

    @Test
    void aDraftHasExactLineTotalsAndNoNumber() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        Resp r = draft(b, c, "2026-03-01", null, "INR", List.of(line("Design", "3", "33.33", "10.00", "18"), line("Hosting", "0.5", "1.01")), null);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        assertThat(r.text("status")).isEqualTo("DRAFT");
        assertThat(r.body().path("number").isNull()).isTrue();
        assertThat(r.text("subtotal")).isEqualTo("100.50");
        assertThat(r.text("discount_total")).isEqualTo("10.00");
        assertThat(r.text("tax_total")).isEqualTo("16.20");
        assertThat(r.text("total")).isEqualTo("106.70");
        assertThat(r.text("outstanding")).isEqualTo("0.00");
        assertThat(r.body().path("lines")).hasSize(2);
        assertThat(r.body().path("lines").get(0).path("line_total").asString()).isEqualTo("106.19");
        assertThat(r.text("due_date")).isEqualTo("2026-03-31"); // default terms: 30 days
    }

    @Test
    void numbersAreAssignedAtIssueInSequenceAndIssuedInvoicesAreFrozen() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String first = draftInvoice(b, c, "100.00");
        String second = draftInvoice(b, c, "200.00");
        assertThat(bget(b, "/business-settings").text("next_invoice_number_preview")).isEqualTo("INV-0001");
        Resp i2 = issue(b, second, "200.00");
        assertThat(i2.status()).as(i2.raw().body()).isEqualTo(200);
        assertThat(i2.text("number")).isEqualTo("INV-0001");
        assertThat(i2.text("status")).isEqualTo("ISSUED");
        assertThat(i2.text("outstanding")).isEqualTo("200.00");
        assertThat(issue(b, first, "100.00").text("number")).isEqualTo("INV-0002");

        Resp edit = bpatch(b, "/invoices/" + first, Map.of("version", 2, "notes", "sneaky"));
        assertCode(edit, 409, "INVOICE_IMMUTABLE");
        Resp again = issue(b, first, "100.00");
        assertCode(again, 409, "INVALID_STATE_TRANSITION");
        assertThat(bget(b, "/business-settings").text("next_invoice_number_preview")).isEqualTo("INV-0003");
    }

    @Test
    void numberFormatIsConfigurableAndTheSequenceOnlyMovesForward() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        Resp s = bput(b, "/business-settings", Map.of("invoice_prefix", "FY26/", "invoice_padding", 3, "invoice_next_number", 10));
        assertThat(s.status()).as(s.raw().body()).isEqualTo(200);
        assertThat(issue(b, draftInvoice(b, c, "10.00"), "10.00").text("number")).isEqualTo("FY26/010");
        assertCode(bput(b, "/business-settings", Map.of("invoice_next_number", 5)), 409, "CONFLICT");
        assertThat(bput(b, "/business-settings", Map.of("invoice_prefix", "bad prefix!")).status()).isEqualTo(422);
        assertThat(bput(b, "/business-settings", Map.of("invoice_padding", 0)).status()).isEqualTo(422);
        assertThat(bput(b, "/business-settings", Map.of("timezone", "Mars/Base")).status()).isEqualTo(422);
    }

    @Test
    void concurrentIssuesGetUniqueGapFreeNumbers() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ids.add(draftInvoice(b, c, "10.00"));
        }
        List<Callable<Integer>> calls = new ArrayList<>();
        for (String id : ids) {
            calls.add(() -> issue(b, id, "10.00").status());
        }
        assertThat(concurrently(calls)).containsOnly(200);
        Set<String> numbers = new HashSet<>();
        for (String id : ids) {
            numbers.add(bget(b, "/invoices/" + id).text("number"));
        }
        Set<String> expected = new HashSet<>();
        for (int i = 1; i <= 8; i++) {
            expected.add("INV-000" + i);
        }
        assertThat(numbers).isEqualTo(expected);
    }

    @Test
    void concurrentIssueOfTheSameInvoiceNumbersItOnce() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String id = draftInvoice(b, c, "10.00");
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            calls.add(() -> issue(b, id, "10.00").status());
        }
        List<Integer> statuses = concurrently(calls);
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(4);
        assertThat(count("select count(*) from invoices where tenant_id = :p0 and number is not null", b.space())).isEqualTo(1);
        assertThat(bget(b, "/business-settings").text("next_invoice_number_preview")).isEqualTo("INV-0002");
    }

    @Test
    void aRetriedIssueWithTheSameKeyReplaysAndNumbersOnce() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String id = draftInvoice(b, c, "10.00");
        String key = UUID.randomUUID().toString();
        Resp first = bpostKeyed(b, "/invoices/" + id + "/issue", Map.of("confirm", true, "expected_total", "10.00"), key);
        Resp retry = bpostKeyed(b, "/invoices/" + id + "/issue", Map.of("confirm", true, "expected_total", "10.00"), key);
        assertThat(first.status()).isEqualTo(200);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(retry.text("number")).isEqualTo(first.text("number"));
        assertThat(bget(b, "/business-settings").text("next_invoice_number_preview")).isEqualTo("INV-0002");
        // The same key with a different request is refused.
        assertThat(bpostKeyed(b, "/invoices/" + id + "/issue", Map.of("confirm", true, "expected_total", "11.00"), key).status()).isEqualTo(422);
        // Issue requires a key.
        String other = draftInvoice(b, c, "10.00");
        assertThat(as(b, b.owner(), "OWNER", "POST", "/invoices/" + other + "/issue", Map.of("confirm", true, "expected_total", "10.00")).status()).isEqualTo(400);
    }

    @Test
    void aFailedIssueConsumesNoNumber() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String id = draftInvoice(b, c, "10.00");
        assertCode(issue(b, id, "9.99"), 409, "TOTAL_MISMATCH");
        assertThat(bpost(b, "/invoices/" + id + "/issue", Map.of("expected_total", "10.00")).status()).isEqualTo(422);
        assertThat(bpost(b, "/invoices/" + id + "/issue", Map.of("confirm", false, "expected_total", "10.00")).status()).isEqualTo(422);
        assertThat(bget(b, "/invoices/" + id).text("status")).isEqualTo("DRAFT");
        assertThat(bget(b, "/business-settings").text("next_invoice_number_preview")).isEqualTo("INV-0001");
        assertThat(issue(b, id, "10.00").text("number")).isEqualTo("INV-0001");
    }

    @Test
    void zeroTotalInvoicesCannotBeIssued() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String id = ok(draft(b, c, "2026-03-01", null, "INR", List.of(line("Free", "1", "0.00")), null));
        assertThat(issue(b, id, "0.00").status()).isEqualTo(422);
    }

    @Test
    void cancellingFollowsTheLifecycle() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String d = draftInvoice(b, c, "10.00");
        Resp discard = bpost(b, "/invoices/" + d + "/cancel", Map.of("confirm", true, "reason", "Typed wrong client"));
        assertThat(discard.status()).as(discard.raw().body()).isEqualTo(200);
        assertThat(discard.text("status")).isEqualTo("CANCELLED");
        assertThat(discard.body().path("number").isNull()).isTrue();
        assertCode(issue(b, d, "10.00"), 409, "INVALID_STATE_TRANSITION");

        String i = issued(b, c, "20.00");
        assertThat(bpost(b, "/invoices/" + i + "/cancel", Map.of("reason", "x")).status()).isEqualTo(422); // needs confirm
        assertThat(bpost(b, "/invoices/" + i + "/cancel", Map.of("confirm", true)).status()).isEqualTo(422); // needs reason
        Resp cancelled = bpost(b, "/invoices/" + i + "/cancel", Map.of("confirm", true, "reason", "Duplicate"));
        assertThat(cancelled.text("display_status")).isEqualTo("CANCELLED");
        assertThat(cancelled.text("number")).isEqualTo("INV-0001"); // the number stays on the record
        assertThat(cancelled.text("outstanding")).isEqualTo("0.00");
        assertCode(bpost(b, "/invoices/" + i + "/cancel", Map.of("confirm", true, "reason", "again")), 409, "INVALID_STATE_TRANSITION");
        assertCode(bpatch(b, "/invoices/" + i, Map.of("version", 99)), 409, "INVOICE_IMMUTABLE");
    }

    @Test
    void draftsCanBeEditedWithLinesReplacedAndStaleVersionsAreRefused() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        Resp created = draft(b, c, "2026-03-01", null, "INR", List.of(line("A", "1", "10.00")), null);
        String id = created.id();
        Resp edited = bpatch(b, "/invoices/" + id, Map.of("version", 0, "lines", List.of(line("B", "2", "7.50"), line("C", "1", "1.00")), "notes", "Thanks"));
        assertThat(edited.status()).as(edited.raw().body()).isEqualTo(200);
        assertThat(edited.text("total")).isEqualTo("16.00");
        assertThat(edited.body().path("lines")).hasSize(2);
        assertThat(edited.text("notes")).isEqualTo("Thanks");
        assertThat(edited.body().path("version").asInt()).isEqualTo(1);
        assertCode(bpatch(b, "/invoices/" + id, Map.of("version", 0, "notes", "stale")), 409, "CONFLICT");
        // moving the invoice date keeps the number of days of terms
        Resp moved = bpatch(b, "/invoices/" + id, Map.of("version", 1, "invoice_date", "2026-04-01"));
        assertThat(moved.text("due_date")).isEqualTo("2026-05-01");
        assertThat(count("select count(*) from invoice_lines where tenant_id = :p0 and invoice_id = :p1", b.space(), UUID.fromString(id))).isEqualTo(2);
    }

    @Test
    void dueDatesComeFromExplicitDatesThenClientTermsThenTheDefaultAndNeverPrecedeTheInvoiceDate() throws Exception {
        Biz b = biz();
        String plain = client(b, "Plain");
        String quick = ok(bpost(b, "/clients", Map.of("name", "Quick", "payment_terms_days", 10)));
        assertThat(draft(b, plain, "2026-03-01", null, "INR", List.of(line("A", "1", "1")), null).text("due_date")).isEqualTo("2026-03-31");
        assertThat(draft(b, quick, "2026-03-01", null, "INR", List.of(line("A", "1", "1")), null).text("due_date")).isEqualTo("2026-03-11");
        assertThat(draft(b, quick, "2026-03-01", "2026-03-01", "INR", List.of(line("A", "1", "1")), null).text("due_date")).isEqualTo("2026-03-01");
        assertThat(draft(b, quick, "2026-03-01", "2026-02-28", "INR", List.of(line("A", "1", "1")), null).status()).isEqualTo(422);
    }

    @Test
    void overdueFlipsAtTheBusinessMidnightAndTheDueDateItselfIsNotOverdue() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        // Business time zone defaults to Asia/Kolkata (UTC+05:30): at 10:00Z it is 2026-03-15 15:30 there.
        String due15 = ok(draft(b, c, "2026-03-01", "2026-03-15", "INR", List.of(line("A", "1", "50.00")), null));
        String due14 = ok(draft(b, c, "2026-03-01", "2026-03-14", "INR", List.of(line("A", "1", "50.00")), null));
        ok(issue(b, due15, "50.00"));
        ok(issue(b, due14, "50.00"));
        assertThat(status(b, due15)).isEqualTo("UNPAID");
        Resp late = bget(b, "/invoices/" + due14);
        assertThat(late.text("display_status")).isEqualTo("OVERDUE");
        assertThat(late.body().path("days_overdue").asLong()).isEqualTo(1);
        assertThat(bget(b, "/invoices?status=OVERDUE").body().path("total").asLong()).isEqualTo(1);

        clock.set("2026-03-15T18:29:59Z"); // 23:59:59 in Kolkata, still the 15th
        assertThat(status(b, due15)).isEqualTo("UNPAID");
        clock.set("2026-03-15T18:30:00Z"); // midnight in Kolkata
        assertThat(status(b, due15)).isEqualTo("OVERDUE");
        assertThat(bget(b, "/invoices/" + due15).body().path("days_overdue").asLong()).isEqualTo(1);
        assertThat(bget(b, "/invoices?status=OVERDUE").body().path("total").asLong()).isEqualTo(2);
        assertThat(bget(b, "/invoices?status=UNPAID").body().path("total").asLong()).isEqualTo(0);
    }

    @Test
    void moneyAndQuantityValidationRefusesAnythingImpreciseOrNonsensical() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        record Case(String name, List<Map<String, Object>> lines, String currency) {
        }
        List<Case> bad = List.of(
                new Case("too many decimals for INR", List.of(line("A", "1", "10.001")), "INR"),
                new Case("decimals for JPY", List.of(line("A", "1", "10.5")), "JPY"),
                new Case("quantity with 5 decimals", List.of(line("A", "1.00001", "10")), "INR"),
                new Case("zero quantity", List.of(line("A", "0", "10")), "INR"),
                new Case("negative quantity", List.of(line("A", "-1", "10")), "INR"),
                new Case("negative price", List.of(line("A", "1", "-10")), "INR"),
                new Case("discount above the line", List.of(line("A", "1", "10", "10.01", "0")), "INR"),
                new Case("negative discount", List.of(line("A", "1", "10", "-1", "0")), "INR"),
                new Case("tax above 100", List.of(line("A", "1", "10", "0", "100.01")), "INR"),
                new Case("tax with 3 decimals", List.of(line("A", "1", "10", "0", "5.123")), "INR"),
                new Case("float notation", List.of(line("A", "1", "1e3")), "INR"),
                new Case("no lines", List.of(), "INR"),
                new Case("unsupported currency", List.of(line("A", "1", "10")), "XXX"));
        for (Case k : bad) {
            Resp r = draft(b, c, "2026-03-01", null, k.currency(), k.lines(), null);
            assertThat(r.status()).as(k.name() + " -> " + r.raw().body()).isEqualTo(422);
        }
        assertThat(count("select count(*) from invoices where tenant_id = :p0", b.space())).isZero();
        // JPY with whole numbers is fine, and 100 lines is the limit.
        assertThat(draft(b, c, "2026-03-01", null, "JPY", List.of(line("A", "3", "1000", "100", "10")), null).text("total")).isEqualTo("3190");
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(line("L" + i, "1", "1"));
        }
        assertThat(draft(b, c, "2026-03-01", null, "INR", many, null).status()).isEqualTo(422);
    }

    @Test
    void aBadSecondLineRollsBackTheWholeInvoice() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        Resp r = draft(b, c, "2026-03-01", null, "INR", List.of(line("Good", "1", "10.00"), line("Bad", "1", "10.001")), null);
        assertThat(r.status()).isEqualTo(422);
        assertThat(count("select count(*) from invoices where tenant_id = :p0", b.space())).isZero();
        assertThat(count("select count(*) from invoice_lines where tenant_id = :p0", b.space())).isZero();
    }

    @Test
    void projectAndClientRulesAreEnforced() throws Exception {
        Biz b = biz();
        String c1 = client(b, "One");
        String c2 = client(b, "Two");
        String p1 = project(b, "Site", c1, "INR", null);
        String usd = project(b, "Export", null, "USD", null);
        assertThat(draft(b, c1, "2026-03-01", null, "INR", List.of(line("A", "1", "1")), p1).status()).isEqualTo(201);
        assertThat(draft(b, c2, "2026-03-01", null, "INR", List.of(line("A", "1", "1")), p1).status()).as("another client's project").isEqualTo(422);
        assertThat(draft(b, c1, "2026-03-01", null, "INR", List.of(line("A", "1", "1")), usd).status()).as("project currency").isEqualTo(422);
        ok(bpatch(b, "/projects/" + p1, Map.of("version", 0, "status", "COMPLETED")));
        assertThat(draft(b, c1, "2026-03-01", null, "INR", List.of(line("A", "1", "1")), p1).status()).as("closed project").isEqualTo(409);
        ok(bpost(b, "/clients/" + c2 + "/archive", Map.of()));
        assertThat(draft(b, c2, "2026-03-01", null, "INR", List.of(line("A", "1", "1")), null).status()).as("archived client").isEqualTo(422);
        assertThat(draft(b, UUID.randomUUID().toString(), "2026-03-01", null, "INR", List.of(line("A", "1", "1")), null).status()).isEqualTo(422);
    }

    @Test
    void listsPageFilterAndSearchDeterministically() throws Exception {
        Biz b = biz();
        String acme = client(b, "Acme Traders");
        String zed = client(b, "Zed Works");
        String i1 = issued(b, acme, "10.00");
        String i2 = issued(b, zed, "20.00");
        String i3 = issued(b, acme, "30.00");
        draftInvoice(b, acme, "40.00");
        draftInvoice(b, acme, "50.00");
        Resp page1 = bget(b, "/invoices?limit=2&offset=0");
        Resp page2 = bget(b, "/invoices?limit=2&offset=2");
        Resp page3 = bget(b, "/invoices?limit=2&offset=4");
        assertThat(page1.body().path("total").asLong()).isEqualTo(5);
        assertThat(page1.body().path("items")).hasSize(2);
        assertThat(page2.body().path("items")).hasSize(2);
        assertThat(page3.body().path("items")).hasSize(1);
        Set<String> seen = new HashSet<>();
        for (Resp p : List.of(page1, page2, page3)) {
            p.body().path("items").forEach(n -> seen.add(n.path("id").asString()));
        }
        assertThat(seen).hasSize(5);
        assertThat(bget(b, "/invoices?client_id=" + zed).body().path("total").asLong()).isEqualTo(1);
        assertThat(bget(b, "/invoices?status=DRAFT").body().path("total").asLong()).isEqualTo(2);
        assertThat(bget(b, "/invoices?status=ISSUED").body().path("total").asLong()).isEqualTo(3);
        assertThat(bget(b, "/invoices?q=zed").body().path("total").asLong()).isEqualTo(1);
        assertThat(bget(b, "/invoices?q=INV-0003").body().path("items").get(0).path("id").asString()).isEqualTo(i3);
        assertThat(bget(b, "/invoices?q=%25").body().path("total").asLong()).as("LIKE wildcards are literal").isEqualTo(0);
        assertThat(bget(b, "/invoices?status=NOPE").status()).isEqualTo(422);
        assertThat(bget(b, "/invoices?limit=101").status()).isEqualTo(422);
        assertThat(bget(b, "/invoices?limit=0").status()).isEqualTo(422);
        assertThat(bget(b, "/invoices?offset=-1").status()).isEqualTo(422);
        assertThat(bget(b, "/invoices?from=bad").status()).isEqualTo(422);
        assertThat(i1).isNotEqualTo(i2);
    }
}
