package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Reconciliation of imported statement lines with existing records: suggestions, explicit confirmation, undo, isolation, roles. */
class ReconciliationTest extends BusinessTestSupport {

    private static final Map<String, String> MAPPING = Map.of("date", "Date", "amount", "Amount", "description", "Description", "reference", "Ref");

    /** Imports statement lines for a personal account and returns the job id. */
    private String statementP(Person p, String account, String rows) throws Exception {
        Resp u = upload(base(p) + "/finance-imports?kind=STATEMENT&filename=bank.csv&account_id=" + account, token(p), ("Date,Amount,Description,Ref\n" + rows).getBytes(StandardCharsets.UTF_8), "text/csv");
        assertThat(u.status()).as(u.raw().body()).isEqualTo(201);
        assertThat(put(p, "/finance-imports/" + u.id() + "/mapping", Map.of("fields", MAPPING, "options", Map.of())).status()).isEqualTo(200);
        Resp c = post(p, "/finance-imports/" + u.id() + "/commit", Map.of("confirm", true, "possible_duplicates", "SKIP"));
        assertThat(c.text("status")).as(c.raw().body()).isEqualTo("COMPLETED");
        return u.id();
    }

    private String statementB(Biz b, String rows) throws Exception {
        Resp u = upload("/v1/spaces/" + b.space() + "/finance-imports?kind=STATEMENT&filename=bank.csv", btoken(b, b.owner(), "OWNER"), ("Date,Amount,Description,Ref\n" + rows).getBytes(StandardCharsets.UTF_8), "text/csv");
        assertThat(u.status()).as(u.raw().body()).isEqualTo(201);
        assertThat(bput(b, "/finance-imports/" + u.id() + "/mapping", Map.of("fields", MAPPING, "options", Map.of())).status()).isEqualTo(200);
        assertThat(bpost(b, "/finance-imports/" + u.id() + "/commit", Map.of("confirm", true, "possible_duplicates", "SKIP")).text("status")).isEqualTo("COMPLETED");
        return u.id();
    }

    private JsonNode line(Resp page, String description) {
        for (JsonNode l : page.body().path("items")) {
            if (description.equals(l.path("description").asString())) {
                return l;
            }
        }
        throw new AssertionError("no line " + description + " in " + page.raw().body());
    }

    private String mk(Person p, String kind, String account, String amount, String date, String payee, String note) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("kind", kind, "account_id", account, "amount", amount, "occurred_on", date));
        if (payee != null) {
            body.put("payee", payee);
        }
        if (note != null) {
            body.put("note", note);
        }
        return ok(post(p, "/transactions", body));
    }

    private record World(Person p, String acc, String t1, String t2, String t3, String t4, String t5) {
    }

    private World world() throws Exception {
        Person p = person();
        String acc = bank(p, "Main", "0.00");
        String t1 = mk(p, "EXPENSE", acc, "250.50", "2026-03-01", "Grocer", "REF-1");
        String t2 = mk(p, "INCOME", acc, "1000.00", "2026-03-02", null, null);
        String t3 = mk(p, "EXPENSE", acc, "40.00", "2026-03-05", null, null);
        String t4 = mk(p, "EXPENSE", acc, "40.00", "2026-03-05", null, null);
        String t5 = mk(p, "EXPENSE", acc, "99.99", "2026-03-10", null, null);
        String savings = bank(p, "Savings", "0.00");
        ok(post(p, "/transfers", Map.of("from_account_id", acc, "to_account_id", savings, "amount", "500.00", "occurred_on", "2026-03-06")));
        statementP(p, acc, "2026-03-01,-250.50,GROCER,REF-1\n2026-03-02,1000.00,SALARY,\n2026-03-05,-40.00,CARD A,\n2026-03-12,-99.99,SHOP,\n2026-03-20,-5.00,FEE,\n"
                + "2026-03-02,-1000.00,WRONG WAY,\n2026-03-01,-250.51,A CENT OFF,\n");
        return new World(p, acc, t1, t2, t3, t4, t5);
    }

    @Test
    void suggestionsAreTransparentAndNeverApplied() throws Exception {
        World w = world();
        Resp page = get(w.p(), "/finance-reconciliation/lines?limit=50");
        assertThat(page.status()).as(page.raw().body()).isEqualTo(200);
        assertThat(page.body().path("total").asLong()).isEqualTo(7);
        JsonNode exact = line(page, "GROCER");
        assertThat(exact.path("suggestion").asString()).isEqualTo("STRONG");
        assertThat(exact.path("candidates")).hasSize(1);
        JsonNode c = exact.path("candidates").get(0);
        assertThat(c.path("target_id").asString()).isEqualTo(w.t1());
        assertThat(c.path("score").asInt()).isEqualTo(100);
        assertThat(c.path("signals").toString()).contains("same amount and currency").contains("same date").contains("same reference").contains("name appears");
        assertThat(line(page, "SALARY").path("suggestion").asString()).isEqualTo("STRONG");
        assertThat(line(page, "SALARY").path("candidates").get(0).path("score").asInt()).isEqualTo(80);
        JsonNode ambiguous = line(page, "CARD A");
        assertThat(ambiguous.path("suggestion").asString()).as("two equal candidates are never settled by the system").isEqualTo("AMBIGUOUS");
        assertThat(ambiguous.path("candidates")).hasSize(2);
        JsonNode near = line(page, "SHOP");
        assertThat(near.path("suggestion").asString()).isEqualTo("WEAK");
        assertThat(near.path("candidates").get(0).path("target_id").asString()).isEqualTo(w.t5());
        assertThat(near.path("candidates").get(0).path("score").asInt()).isEqualTo(60);
        assertThat(near.path("candidates").get(0).path("signals").toString()).contains("2 days apart");
        assertThat(line(page, "FEE").path("suggestion").asString()).isEqualTo("NONE");
        assertThat(line(page, "WRONG WAY").path("candidates")).as("an outflow never matches an income").isEmpty();
        assertThat(line(page, "A CENT OFF").path("candidates")).as("amounts must be exactly equal").isEmpty();
        // nothing was reconciled by looking
        assertThat(count("select count(*) from finance_reconciliation_matches where tenant_id = :p0", w.p().space())).isZero();
        assertThat(get(w.p(), "/finance-reconciliation/summary").body().path("unmatched").asLong()).isEqualTo(7);
    }

    @Test
    void aMatchExistsOnlyAfterExplicitConfirmationAndEachSideMatchesOnce() throws Exception {
        World w = world();
        JsonNode grocer = line(get(w.p(), "/finance-reconciliation/lines?limit=50"), "GROCER");
        String path = "/finance-reconciliation/lines/" + grocer.path("id").asString() + "/match";
        assertThat(post(w.p(), path, Map.of("target_type", "TRANSACTION", "target_id", w.t1())).status()).as("confirm must be true").isEqualTo(422);
        assertThat(post(w.p(), path, Map.of("confirm", true, "target_type", "PAYMENT", "target_id", w.t1())).status()).as("wrong kind of record for a personal profile").isEqualTo(422);
        assertThat(post(w.p(), path, Map.of("confirm", true, "target_type", "TRANSACTION", "target_id", "not-a-uuid")).status()).isEqualTo(422);
        assertThat(post(w.p(), path, Map.of("confirm", true, "target_type", "TRANSACTION", "target_id", w.t2())).status()).as("income cannot match an outflow").isEqualTo(409);
        assertThat(post(w.p(), path, Map.of("confirm", true, "target_type", "TRANSACTION", "target_id", UUID.randomUUID().toString())).status()).isEqualTo(409);
        Resp matched = post(w.p(), path, Map.of("confirm", true, "target_type", "TRANSACTION", "target_id", w.t1()));
        assertThat(matched.status()).as(matched.raw().body()).isEqualTo(200);
        assertThat(matched.text("status")).isEqualTo("MATCHED");
        assertThat(matched.body().path("match").path("target_id").asString()).isEqualTo(w.t1());
        assertThat(matched.body().path("match").path("score").asInt()).isEqualTo(100);
        assertThat(matched.body().path("match").path("target_still_valid").asBoolean()).isTrue();
        // the same confirmation again changes nothing; a different record for a matched line is refused
        assertThat(post(w.p(), path, Map.of("confirm", true, "target_type", "TRANSACTION", "target_id", w.t1())).status()).isEqualTo(200);
        assertThat(count("select count(*) from finance_reconciliation_matches where tenant_id = :p0 and status = 'CONFIRMED'", w.p().space())).isEqualTo(1);
        // a second line with the same amount cannot take the already reconciled record
        statementP(w.p(), w.acc(), "2026-03-01,-250.50,GROCER AGAIN,REF-1\n");
        JsonNode again = line(get(w.p(), "/finance-reconciliation/lines?limit=50"), "GROCER AGAIN");
        assertThat(again.path("candidates")).as("an already reconciled record is no longer offered").isEmpty();
        assertThat(post(w.p(), "/finance-reconciliation/lines/" + again.path("id").asString() + "/match", Map.of("confirm", true, "target_type", "TRANSACTION", "target_id", w.t1())).status()).isEqualTo(409);
        assertThat(get(w.p(), "/finance-reconciliation/lines?status=MATCHED").body().path("items")).hasSize(1);
        // unmatched records: inside the statement period, not reconciled; transfers are not offered
        Resp unmatched = get(w.p(), "/finance-reconciliation/unmatched-records");
        assertThat(unmatched.body().path("items")).hasSize(4);
        for (JsonNode r : unmatched.body().path("items")) {
            assertThat(r.path("target_id").asString()).isNotEqualTo(w.t1());
        }
        assertThat(get(w.p(), "/finance-reconciliation/summary").body().path("matched").asLong()).isEqualTo(1);
    }

    @Test
    void twoLinesRacingForOneRecordProduceExactlyOneMatch() throws Exception {
        World w = world();
        statementP(w.p(), w.acc(), "2026-03-05,-40.00,CARD B,\n");
        Resp page = get(w.p(), "/finance-reconciliation/lines?limit=50");
        String a = line(page, "CARD A").path("id").asString();
        String b = line(page, "CARD B").path("id").asString();
        List<Callable<Integer>> calls = new ArrayList<>();
        for (String id : List.of(a, b)) {
            calls.add(() -> post(w.p(), "/finance-reconciliation/lines/" + id + "/match", Map.of("confirm", true, "target_type", "TRANSACTION", "target_id", w.t3())).status());
        }
        List<Integer> statuses = concurrently(calls);
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(count("select count(*) from finance_reconciliation_matches where tenant_id = :p0 and target_id = :p1 and status = 'CONFIRMED'", w.p().space(), UUID.fromString(w.t3()))).isEqualTo(1);
    }

    @Test
    void undoingFreesBothSidesAndKeepsTheHistory() throws Exception {
        World w = world();
        JsonNode salary = line(get(w.p(), "/finance-reconciliation/lines?limit=50"), "SALARY");
        String lineId = salary.path("id").asString();
        Resp matched = post(w.p(), "/finance-reconciliation/lines/" + lineId + "/match", Map.of("confirm", true, "target_type", "TRANSACTION", "target_id", w.t2()));
        String matchId = matched.body().path("match").path("id").asString();
        assertThat(post(w.p(), "/finance-reconciliation/matches/" + matchId + "/undo", Map.of()).status()).as("a reason is required").isEqualTo(422);
        Resp undone = post(w.p(), "/finance-reconciliation/matches/" + matchId + "/undo", Map.of("reason", "Matched the wrong salary"));
        assertThat(undone.status()).as(undone.raw().body()).isEqualTo(200);
        assertThat(undone.text("status")).isEqualTo("UNMATCHED");
        assertThat(undone.body().path("candidates").get(0).path("target_id").asString()).as("the record can be offered again").isEqualTo(w.t2());
        assertThat(post(w.p(), "/finance-reconciliation/matches/" + matchId + "/undo", Map.of("reason", "again")).status()).isEqualTo(409);
        assertThat(count("select count(*) from finance_reconciliation_matches where tenant_id = :p0 and status = 'UNDONE' and undo_reason = 'Matched the wrong salary'", w.p().space())).as("history is kept").isEqualTo(1);
        assertThat(count("select count(*) from finance_audit_events where tenant_id = :p0 and event_type in ('RECONCILIATION_CONFIRMED', 'RECONCILIATION_UNDONE')", w.p().space())).isEqualTo(2);
        // it can be matched again, and a match whose record was voided afterwards says so
        assertThat(post(w.p(), "/finance-reconciliation/lines/" + lineId + "/match", Map.of("confirm", true, "target_type", "TRANSACTION", "target_id", w.t2())).status()).isEqualTo(200);
        ok(post(w.p(), "/transactions/" + w.t2() + "/void", Map.of("reason", "wrong")));
        Resp after = get(w.p(), "/finance-reconciliation/lines/" + lineId);
        assertThat(after.body().path("match").path("target_still_valid").asBoolean()).isFalse();
        assertThat(post(w.p(), "/finance-reconciliation/matches/" + UUID.randomUUID() + "/undo", Map.of("reason", "x")).status()).isEqualTo(404);
    }

    @Test
    void linesCanBeIgnoredAndRestoredAndAVoidedRecordIsNeverACandidate() throws Exception {
        World w = world();
        JsonNode fee = line(get(w.p(), "/finance-reconciliation/lines?limit=50"), "FEE");
        String id = fee.path("id").asString();
        assertThat(post(w.p(), "/finance-reconciliation/lines/" + id + "/ignore", Map.of()).status()).isEqualTo(422);
        Resp ignored = post(w.p(), "/finance-reconciliation/lines/" + id + "/ignore", Map.of("reason", "Bank fee, not recorded"));
        assertThat(ignored.text("status")).isEqualTo("IGNORED");
        assertThat(ignored.text("ignore_reason")).isEqualTo("Bank fee, not recorded");
        assertThat(post(w.p(), "/finance-reconciliation/lines/" + id + "/match", Map.of("confirm", true, "target_type", "TRANSACTION", "target_id", w.t5())).status()).isEqualTo(409);
        assertThat(get(w.p(), "/finance-reconciliation/lines?status=IGNORED").body().path("items")).hasSize(1);
        assertThat(post(w.p(), "/finance-reconciliation/lines/" + id + "/restore", Map.of()).text("status")).isEqualTo("UNMATCHED");
        assertThat(post(w.p(), "/finance-reconciliation/lines/" + id + "/restore", Map.of()).status()).isEqualTo(409);
        ok(post(w.p(), "/transactions/" + w.t5() + "/void", Map.of("reason", "oops")));
        assertThat(line(get(w.p(), "/finance-reconciliation/lines?limit=50"), "SHOP").path("candidates")).isEmpty();
        assertThat(get(w.p(), "/finance-reconciliation/lines?status=BOGUS").status()).isEqualTo(422);
        assertThat(get(w.p(), "/finance-reconciliation/lines?limit=51").status()).isEqualTo(422);
    }

    @Test
    void personalReconciliationDataStaysInItsOwnWorkspace() throws Exception {
        World w = world();
        Person other = person();
        String otherAcc = bank(other, "Main", "0.00");
        String foreignTx = mk(other, "EXPENSE", otherAcc, "250.50", "2026-03-01", "Grocer", "REF-1");
        JsonNode grocer = line(get(w.p(), "/finance-reconciliation/lines?limit=50"), "GROCER");
        String id = grocer.path("id").asString();
        assertThat(get(other, "/finance-reconciliation/lines").body().path("items")).isEmpty();
        assertThat(get(other, "/finance-reconciliation/lines/" + id).status()).isEqualTo(404);
        assertThat(post(other, "/finance-reconciliation/lines/" + id + "/match", Map.of("confirm", true, "target_type", "TRANSACTION", "target_id", w.t1())).status()).isEqualTo(404);
        assertThat(post(other, "/finance-reconciliation/lines/" + id + "/ignore", Map.of("reason", "x")).status()).isEqualTo(404);
        // a foreign record is not a valid target for my line even with the exact same amount and date
        assertThat(post(w.p(), "/finance-reconciliation/lines/" + id + "/match", Map.of("confirm", true, "target_type", "TRANSACTION", "target_id", foreignTx)).status()).isEqualTo(409);
        assertThat(get(other, "/finance-reconciliation/summary").body().path("unmatched").asLong()).isZero();
        assertThat(get(other, "/finance-reconciliation/unmatched-records").body().path("items")).isEmpty();
        Biz b = biz();
        assertThat(bget(b, "/finance-reconciliation/lines").body().path("items")).isEmpty();
        assertThat(raw("GET", "/v1/spaces/" + w.p().space() + "/finance-reconciliation/lines", btoken(b, b.owner(), "OWNER"), null).status()).as("a business token on a personal path").isEqualTo(404);
    }

    @Test
    void businessLinesMatchPaymentsReceivedAndExpensesNotVoidedOnes() throws Exception {
        Biz b = biz();
        String client = client(b, "Acme");
        String invoice = issued(b, client, "500.00");
        String payment = ok(bpost(b, "/payments", Map.of("client_id", client, "payment_date", "2026-03-10", "amount", "500.00", "currency", "INR", "method", "BANK_TRANSFER", "reference", "UTR-9",
                "allocations", List.of(alloc(invoice, "500.00")))));
        String voided = ok(bpost(b, "/payments", Map.of("client_id", client, "payment_date", "2026-03-10", "amount", "500.00", "currency", "INR", "method", "CASH", "reference", "UTR-8")));
        ok(bpost(b, "/payments/" + voided + "/void", Map.of("reason", "bounced")));
        String vendor = vendor(b, "Cement Co");
        String exp = ok(expense(b, "300.00", "2026-03-11", vendor, null, null));
        statementB(b, "2026-03-10,500.00,NEFT ACME,UTR-9\n2026-03-11,-300.00,CEMENT CO PAYMENT,\n2026-03-12,-7.00,CHARGES,\n");
        Resp page = bget(b, "/finance-reconciliation/lines?limit=50");
        JsonNode in = line(page, "NEFT ACME");
        assertThat(in.path("candidates")).as("the voided payment is not offered").hasSize(1);
        assertThat(in.path("candidates").get(0).path("target_type").asString()).isEqualTo("PAYMENT");
        assertThat(in.path("candidates").get(0).path("target_id").asString()).isEqualTo(payment);
        assertThat(in.path("candidates").get(0).path("score").asInt()).isEqualTo(100);
        JsonNode out = line(page, "CEMENT CO PAYMENT");
        assertThat(out.path("candidates").get(0).path("target_type").asString()).isEqualTo("EXPENSE");
        assertThat(out.path("candidates").get(0).path("target_id").asString()).isEqualTo(exp);
        assertThat(out.path("candidates").get(0).path("signals").toString()).contains("name appears");
        assertThat(out.path("candidates").get(0).path("has_document").asBoolean()).isFalse();
        // a supporting document linked to the expense shows up as a signal for the reviewer
        jdbc.sql("""
                insert into finance_documents (id, tenant_id, domain, kind, original_filename, content_type, size_bytes, sha256, storage_key, status, linked_type, linked_id, created_by, created_at, updated_at)
                values (gen_random_uuid(), :t, 'BUSINESS', 'INVOICE', 'x.pdf', 'application/pdf', 10, :h, :k, 'CONFIRMED', 'EXPENSE', :e, :u, now(), now())
                """).param("t", b.space()).param("h", "a".repeat(64)).param("k", b.space() + "/" + UUID.randomUUID()).param("e", UUID.fromString(exp)).param("u", b.owner()).update();
        assertThat(line(bget(b, "/finance-reconciliation/lines?limit=50"), "CEMENT CO PAYMENT").path("candidates").get(0).path("has_document").asBoolean()).isTrue();
        // wrong target kinds for the direction are refused
        String lineIn = in.path("id").asString();
        assertThat(bpost(b, "/finance-reconciliation/lines/" + lineIn + "/match", Map.of("confirm", true, "target_type", "EXPENSE", "target_id", exp)).status()).isEqualTo(409);
        assertThat(bpost(b, "/finance-reconciliation/lines/" + lineIn + "/match", Map.of("confirm", true, "target_type", "TRANSACTION", "target_id", payment)).status()).isEqualTo(422);
        assertThat(bpost(b, "/finance-reconciliation/lines/" + lineIn + "/match", Map.of("confirm", true, "target_type", "PAYMENT", "target_id", voided)).status()).as("a voided payment").isEqualTo(409);
        Resp ok = bpost(b, "/finance-reconciliation/lines/" + lineIn + "/match", Map.of("confirm", true, "target_type", "PAYMENT", "target_id", payment));
        assertThat(ok.text("status")).isEqualTo("MATCHED");
        assertThat(bpost(b, "/finance-reconciliation/lines/" + out.path("id").asString() + "/match", Map.of("confirm", true, "target_type", "EXPENSE", "target_id", exp)).text("status")).isEqualTo("MATCHED");
        Resp unmatched = bget(b, "/finance-reconciliation/unmatched-records");
        assertThat(unmatched.body().path("items")).as("only the voided payment's sibling is left out; everything real is reconciled").isEmpty();
        assertThat(bget(b, "/finance-reconciliation/summary").body().path("unmatched").asLong()).isEqualTo(1);
    }

    @Test
    void onlyAccountantsAndOwnersChangeReconciliationAndAuditorsMayRead() throws Exception {
        Biz b = biz();
        UUID user = UUID.randomUUID();
        String exp = ok(expense(b, "300.00", "2026-03-11", null, null, null));
        statementB(b, "2026-03-11,-300.00,SPEND,\n");
        String lineId = line(bget(b, "/finance-reconciliation/lines"), "SPEND").path("id").asString();
        String match = "/finance-reconciliation/lines/" + lineId + "/match";
        Map<String, Object> body = Map.of("confirm", true, "target_type", "EXPENSE", "target_id", exp);
        for (String role : List.of("ADMIN", "MANAGER", "CONTRIBUTOR", "VIEWER")) {
            assertThat(as(b, user, role, "GET", "/finance-reconciliation/lines", null).status()).as(role + " read").isEqualTo(403);
            assertThat(as(b, user, role, "GET", "/finance-reconciliation/summary", null).status()).isEqualTo(403);
            assertThat(as(b, user, role, "POST", match, body).status()).as(role + " match").isEqualTo(403);
        }
        assertThat(as(b, user, "AUDITOR", "GET", "/finance-reconciliation/lines", null).status()).isEqualTo(200);
        assertThat(as(b, user, "AUDITOR", "GET", "/finance-reconciliation/unmatched-records", null).status()).isEqualTo(200);
        assertThat(as(b, user, "AUDITOR", "POST", match, body).status()).as("an Auditor cannot change anything").isEqualTo(403);
        assertThat(as(b, user, "AUDITOR", "POST", "/finance-reconciliation/lines/" + lineId + "/ignore", Map.of("reason", "x")).status()).isEqualTo(403);
        Resp ok = as(b, user, "ACCOUNTANT", "POST", match, body);
        assertThat(ok.status()).isEqualTo(200);
        String matchId = ok.body().path("match").path("id").asString();
        assertThat(as(b, user, "AUDITOR", "POST", "/finance-reconciliation/matches/" + matchId + "/undo", Map.of("reason", "x")).status()).isEqualTo(403);
        assertThat(as(b, user, "ACCOUNTANT", "POST", "/finance-reconciliation/matches/" + matchId + "/undo", Map.of("reason", "changed my mind")).text("status")).isEqualTo("UNMATCHED");
    }
}
