package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/** CSV imports: validation, mapping, preview, commit, duplicates, retries, partial failures, interruption, permissions. */
class ImportsTest extends BusinessTestSupport {

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private Resp up(Person p, String kind, String name, String csv, String account) throws Exception {
        return upload(base(p) + "/finance-imports?kind=" + kind + "&filename=" + enc(name) + (account == null ? "" : "&account_id=" + account), token(p), csv.getBytes(StandardCharsets.UTF_8), "text/csv");
    }

    private Resp upBiz(Biz b, String role, UUID user, String kind, String name, String csv) throws Exception {
        return upload("/v1/spaces/" + b.space() + "/finance-imports?kind=" + kind + "&filename=" + enc(name), btoken(b, user, role), csv.getBytes(StandardCharsets.UTF_8), "text/csv");
    }

    private static Map<String, String> fields(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private Resp map(Person p, String id, Map<String, String> fields, Map<String, String> options) throws Exception {
        return put(p, "/finance-imports/" + id + "/mapping", Map.of("fields", fields, "options", options));
    }

    private Resp commit(Person p, String id, String policy) throws Exception {
        return post(p, "/finance-imports/" + id + "/commit", Map.of("confirm", true, "possible_duplicates", policy));
    }

    /** The service reads time from the test clock (2026-03-15T10:00Z), so leases are set relative to that, not to the wall clock. */
    private static final java.time.OffsetDateTime LIVE = java.time.OffsetDateTime.parse("2026-03-15T11:00:00Z");
    private static final java.time.OffsetDateTime DEAD = java.time.OffsetDateTime.parse("2026-03-15T09:00:00Z");

    private static final String STD = "Txn Date,Amount,Narration,Ref,Category\n";
    private static final Map<String, String> STD_MAP = fields("date", "Txn Date", "amount", "Amount", "description", "Narration", "reference", "Ref", "category", "Category");

    private String ready(Person p, String csv, String account, Map<String, String> mapping) throws Exception {
        Resp u = up(p, "TRANSACTIONS", "bank.csv", csv, account);
        assertThat(u.status()).as(u.raw().body()).isEqualTo(201);
        Resp m = map(p, u.id(), mapping, Map.of());
        assertThat(m.status()).as(m.raw().body()).isEqualTo(200);
        return u.id();
    }

    @Test
    void aFileIsUploadedMappedPreviewedCommittedAndIsNeverCommittedTwice() throws Exception {
        Person p = person();
        String acc = bank(p, "Main", "0.00");
        category(p, "Food", "EXPENSE");
        Resp u = up(p, "TRANSACTIONS", "march.csv", STD + "2026-03-01,-250.50,Groceries,R1,Food\n2026-03-02,1000.00,Salary,R2,\n2026-03-03,-250.50,Groceries,R3,Food\n", acc);
        assertThat(u.status()).as(u.raw().body()).isEqualTo(201);
        assertThat(u.text("status")).isEqualTo("NEEDS_MAPPING");
        assertThat(u.body().path("total_rows").asInt()).isEqualTo(3);
        assertThat(u.body().path("suggested_mapping").path("date").asString()).isEqualTo("Txn Date");
        assertThat(u.body().path("suggested_mapping").path("description").asString()).isEqualTo("Narration");
        assertThat(u.body().path("sample")).hasSize(3);
        assertThat(count("select count(*) from transactions where tenant_id = :p0", p.space())).as("an upload alone writes nothing").isZero();

        Resp m = map(p, u.id(), STD_MAP, Map.of());
        assertThat(m.text("status")).isEqualTo("READY");
        assertThat(m.body().path("valid_rows").asInt()).isEqualTo(3);
        assertThat(m.body().path("invalid_rows").asInt()).isZero();
        Resp rows = get(p, "/finance-imports/" + u.id() + "/rows");
        assertThat(rows.body().path("total").asLong()).isEqualTo(3);
        assertThat(rows.body().path("items").get(0).path("normalized").path("kind").asString()).isEqualTo("EXPENSE");
        assertThat(rows.body().path("items").get(0).path("normalized").path("amount").asString()).isEqualTo("250.50");
        assertThat(rows.body().path("items").get(1).path("normalized").path("kind").asString()).isEqualTo("INCOME");
        assertThat(count("select count(*) from transactions where tenant_id = :p0", p.space())).as("the preview writes nothing either").isZero();

        assertThat(post(p, "/finance-imports/" + u.id() + "/commit", Map.of("possible_duplicates", "SKIP")).status()).as("confirm is required").isEqualTo(422);
        assertThat(post(p, "/finance-imports/" + u.id() + "/commit", Map.of("confirm", true)).status()).as("the duplicate choice is required").isEqualTo(422);
        Resp done = commit(p, u.id(), "SKIP");
        assertThat(done.status()).as(done.raw().body()).isEqualTo(200);
        assertThat(done.text("status")).isEqualTo("COMPLETED");
        assertThat(done.body().path("committed_rows").asInt()).isEqualTo(3);
        assertThat(balance(p, acc)).isEqualTo("499.00");
        // a retry (timeout, double click, second tab) reports the same job and creates nothing
        Resp retry = commit(p, u.id(), "SKIP");
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.text("status")).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from transactions where tenant_id = :p0", p.space())).isEqualTo(3);
        assertThat(balance(p, acc)).isEqualTo("499.00");
        assertThat(commit(p, u.id(), "IMPORT").status()).as("a different choice after the fact").isEqualTo(409);
        assertThat(count("select count(*) from finance_import_ledger where tenant_id = :p0", p.space())).isEqualTo(3);
        assertThat(get(p, "/finance-imports").body().path("items")).hasSize(1);
        assertThat(get(p, "/finance-imports/" + u.id()).text("status")).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from finance_audit_events where tenant_id = :p0 and event_type in ('IMPORT_UPLOADED', 'IMPORT_MAPPED', 'IMPORT_COMPLETED')", p.space())).isEqualTo(3);
    }

    @Test
    void rowsThatFailAreRejectedWithTheReasonAndTheJobIsPartialNeverSuccessful() throws Exception {
        Person p = person();
        String acc = bank(p, "Main", "0.00");
        category(p, "Food", "EXPENSE");
        String csv = "Txn Date,Amount,Narration,Ref,Category,Cur\n"
                + "2026-03-01,-10.00,Good one,G1,Food,INR\n"
                + "2026-13-45,-10.00,Bad date,B1,,INR\n"
                + "2026-03-02,12abc,Bad amount,B2,,INR\n"
                + "2026-03-02,-10.001,Too precise,B3,,INR\n"
                + "2026-03-02,-10.00,Bad currency,B4,,XXX\n"
                + "2026-03-02,-10.00,Wrong currency for the account,B5,,USD\n"
                + ",-10.00,No date,B6,,INR\n"
                + "2026-03-02,-10.00,Unknown category,B7,Nope,INR\n"
                + "2026-03-02,0.00,Zero,B8,,INR\n"
                + "2026-03-03,-10.00,Short row\n"
                + "2026-03-99,-20.00,=SUM(1+1)|cmd,B9,,INR\n"
                + "2026-03-31,+5.00,Another good one,G2,,INR\n";
        String id = up(p, "TRANSACTIONS", "mixed.csv", csv, acc).id();
        Resp m = map(p, id, fields("date", "Txn Date", "amount", "Amount", "description", "Narration", "reference", "Ref", "category", "Category", "currency", "Cur"), Map.of());
        assertThat(m.body().path("valid_rows").asInt()).isEqualTo(2);
        assertThat(m.body().path("invalid_rows").asInt()).isEqualTo(10);
        Resp invalid = get(p, "/finance-imports/" + id + "/rows?status=INVALID&limit=50");
        List<String> problems = new ArrayList<>();
        invalid.body().path("items").forEach(r -> problems.add(r.path("row_number").asInt() + ": " + r.path("errors").get(0).path("message").asString()));
        assertThat(problems.get(0)).startsWith("2: ").contains("not a valid date");
        assertThat(problems.get(1)).startsWith("3: ").contains("number");
        assertThat(problems.get(2)).startsWith("4: ").contains("too many decimals");
        assertThat(problems.get(3)).contains("not a supported currency");
        assertThat(problems.get(4)).contains("account is in INR");
        assertThat(problems.get(5)).contains("date is missing");
        assertThat(problems.get(6)).contains("no category named");
        assertThat(problems.get(7)).contains("cannot be zero");
        assertThat(problems.get(8)).contains("columns but the header has");
        assertThat(problems.get(9)).startsWith("11: ").contains("not a valid date");
        // the original values are kept to explain the failure
        assertThat(invalid.body().path("items").get(0).path("raw").get(0).asString()).isEqualTo("2026-13-45");

        Resp done = commit(p, id, "SKIP");
        assertThat(done.text("status")).as("some rows were rejected, so it is not a plain success").isEqualTo("PARTIAL");
        assertThat(done.body().path("committed_rows").asInt()).isEqualTo(2);
        assertThat(done.body().path("rejected_rows").asInt()).isEqualTo(10);
        assertThat(count("select count(*) from transactions where tenant_id = :p0", p.space())).isEqualTo(2);

        BinResp report = bytes("GET", base(p) + "/finance-imports/" + id + "/error-report", token(p), null, null);
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.header("Content-Type")).startsWith("text/csv");
        String text = report.text();
        assertThat(text).startsWith("row_number,status,problems,Txn Date,Amount,Narration,Ref,Category,Cur\r\n");
        assertThat(text.split("\r\n")).hasSize(11);
        assertThat(text).contains("2026-13-45").contains("not a valid date");
        assertThat(text).as("spreadsheet formulas in the file are neutralised in the error report").contains("'=SUM(1+1)|cmd").doesNotContain(",=SUM");
    }

    @Test
    void asStableIdIsAlwaysADuplicateButSimilarRowsOnlyWarnAndAreImportedOnlyByExplicitChoice() throws Exception {
        Person p = person();
        String acc = bank(p, "Main", "0.00");
        String idMap = "ID,Date,Amount,Memo\n";
        Map<String, String> m = fields("external_id", "ID", "date", "Date", "amount", "Amount", "description", "Memo");
        String first = ready(p, idMap + "A1,2026-03-01,-10.00,One\nA2,2026-03-02,-20.00,Two\n", acc, m);
        assertThat(commit(p, first, "SKIP").text("status")).isEqualTo("COMPLETED");
        String second = ready(p, idMap + "A1,2026-03-01,-10.00,One again\nA3,2026-03-03,-30.00,Three\nA3,2026-03-03,-30.00,Three repeated\n", acc, m);
        Resp rows = get(p, "/finance-imports/" + second + "/rows");
        List<String> statuses = new ArrayList<>();
        rows.body().path("items").forEach(r -> statuses.add(r.path("status").asString()));
        assertThat(statuses).containsExactly("DUPLICATE", "VALID", "DUPLICATE");
        assertThat(rows.body().path("items").get(0).path("possible_duplicate_of").asString()).contains("already imported");
        assertThat(rows.body().path("items").get(2).path("possible_duplicate_of").asString()).contains("earlier in this file");
        Resp done = commit(p, second, "SKIP");
        assertThat(done.text("status")).as("duplicates are skipped, not failures").isEqualTo("COMPLETED");
        assertThat(done.body().path("committed_rows").asInt()).isEqualTo(1);
        assertThat(done.body().path("duplicate_rows").asInt()).isEqualTo(2);
        assertThat(count("select count(*) from transactions where tenant_id = :p0", p.space())).isEqualTo(3);
        // a hard duplicate is skipped even when the user chooses to import possible duplicates
        String third = ready(p, idMap + "A2,2026-03-02,-20.00,Two\n", acc, m);
        Resp t = commit(p, third, "IMPORT");
        assertThat(t.body().path("committed_rows").asInt()).isZero();
        assertThat(t.body().path("duplicate_rows").asInt()).isEqualTo(1);
        assertThat(count("select count(*) from transactions where tenant_id = :p0", p.space())).isEqualTo(3);
        assertThat(count("select count(*) from finance_import_ledger where tenant_id = :p0 and key_type = 'EXTERNAL'", p.space())).isEqualTo(3);
    }

    @Test
    void identicalRowsInOneFileAreBothLegitimateAndALaterIdenticalFileIsFlaggedNotDiscarded() throws Exception {
        Person p = person();
        String acc = bank(p, "Main", "0.00");
        String csv = "Date,Amount,Memo\n2026-03-01,-4.00,Coffee\n2026-03-01,-4.00,Coffee\n2026-03-02,-9.00,Lunch\n";
        Map<String, String> m = fields("date", "Date", "amount", "Amount", "description", "Memo");
        String first = ready(p, csv, acc, m);
        Resp preview = get(p, "/finance-imports/" + first + "/rows");
        preview.body().path("items").forEach(r -> assertThat(r.path("status").asString()).isEqualTo("VALID"));
        Resp done = commit(p, first, "SKIP");
        assertThat(done.body().path("committed_rows").asInt()).as("two real coffees on the same day").isEqualTo(3);
        assertThat(balance(p, acc)).isEqualTo("-17.00");

        String again = ready(p, csv, acc, m);
        assertThat(get(p, "/finance-imports/" + again).body().path("possible_duplicate_rows").asInt()).isEqualTo(3);
        Resp rows = get(p, "/finance-imports/" + again + "/rows");
        assertThat(rows.body().path("items").get(0).path("status").asString()).isEqualTo("POSSIBLE_DUPLICATE");
        assertThat(rows.body().path("items").get(0).path("possible_duplicate_of").asString()).contains("already imported");
        assertThat(post(p, "/finance-imports/" + again + "/commit", Map.of("confirm", true)).status()).isEqualTo(422);
        Resp skipped = commit(p, again, "SKIP");
        assertThat(skipped.body().path("skipped_rows").asInt()).isEqualTo(3);
        assertThat(skipped.body().path("committed_rows").asInt()).isZero();
        assertThat(skipped.text("status")).isEqualTo("COMPLETED");

        String third = ready(p, csv, acc, m);
        Resp imported = commit(p, third, "IMPORT");
        assertThat(imported.body().path("committed_rows").asInt()).as("the user said these are real").isEqualTo(3);
        assertThat(balance(p, acc)).isEqualTo("-34.00");
        // a transaction entered by hand with the same date and amount is also only a warning
        ok(tx(p, "EXPENSE", acc, "77.00", "2026-03-05", null));
        String hand = ready(p, "Date,Amount\n2026-03-05,-77.00\n", acc, fields("date", "Date", "amount", "Amount"));
        Resp r = get(p, "/finance-imports/" + hand + "/rows");
        assertThat(r.body().path("items").get(0).path("status").asString()).isEqualTo("POSSIBLE_DUPLICATE");
        assertThat(r.body().path("items").get(0).path("possible_duplicate_of").asString()).contains("existing record");
    }

    @Test
    void concurrentCommitsOfOneJobCreateEachRecordExactlyOnce() throws Exception {
        Person p = person();
        String acc = bank(p, "Main", "0.00");
        StringBuilder csv = new StringBuilder("ID,Date,Amount\n");
        for (int i = 1; i <= 40; i++) {
            csv.append("T").append(i).append(",2026-03-02,-").append(i).append(".00\n");
        }
        String id = ready(p, csv.toString(), acc, fields("external_id", "ID", "date", "Date", "amount", "Amount"));
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(() -> commit(p, id, "SKIP").status());
        }
        List<Integer> statuses = concurrently(calls);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        assertThat(statuses).contains(200);
        assertThat(get(p, "/finance-imports/" + id).text("status")).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from transactions where tenant_id = :p0", p.space())).isEqualTo(40);
        assertThat(count("select count(*) from finance_import_ledger where tenant_id = :p0", p.space())).isEqualTo(40);
        assertThat(count("select count(*) from finance_import_rows where tenant_id = :p0 and status = 'COMMITTED'", p.space())).isEqualTo(40);
        assertThat(balance(p, acc)).isEqualTo("-820.00");
    }

    @Test
    void anInterruptedCommitResumesWithoutRepeatingRowsAndARunningOneIsNotStartedTwice() throws Exception {
        Person p = person();
        String acc = bank(p, "Main", "0.00");
        String id = ready(p, "ID,Date,Amount\nA,2026-03-01,-1.00\nB,2026-03-02,-2.00\nC,2026-03-03,-3.00\n", acc, fields("external_id", "ID", "date", "Date", "amount", "Amount"));
        // Row A was committed by a worker that then died: the transaction, ledger entry and row status exist, the job is still COMMITTING
        UUID tx = UUID.fromString(ok(tx(p, "EXPENSE", acc, "1.00", "2026-03-01", null)));
        jdbc.sql("update finance_import_rows set status = 'COMMITTED', record_type = 'TRANSACTION', record_id = :r where import_id = :i and row_number = 1").param("r", tx).param("i", UUID.fromString(id)).update();
        jdbc.sql("insert into finance_import_ledger (id, tenant_id, kind, key_type, dedupe_key, import_id, row_number, record_type, record_id, created_at) select gen_random_uuid(), tenant_id, 'TRANSACTIONS', 'EXTERNAL', dedupe_key, id, 1, 'TRANSACTION', :r, now() from (select i.tenant_id, i.id, r.dedupe_key from finance_imports i join finance_import_rows r on r.import_id = i.id and r.row_number = 1 where i.id = :i) x")
                .param("r", tx).param("i", UUID.fromString(id)).update();
        jdbc.sql("update finance_imports set status = 'COMMITTING', possible_duplicate_policy = 'SKIP', lease_until = :l where id = :i").param("l", LIVE).param("i", UUID.fromString(id)).update();
        assertThat(commit(p, id, "SKIP").status()).as("another worker holds a live lease").isEqualTo(409);
        assertThat(count("select count(*) from transactions where tenant_id = :p0", p.space())).isEqualTo(1);
        jdbc.sql("update finance_imports set lease_until = :l where id = :i").param("l", DEAD).param("i", UUID.fromString(id)).update();
        assertThat(commit(p, id, "IMPORT").status()).as("it must resume with the choice already made").isEqualTo(409);
        Resp resumed = commit(p, id, "SKIP");
        assertThat(resumed.status()).as(resumed.raw().body()).isEqualTo(200);
        assertThat(resumed.text("status")).isEqualTo("COMPLETED");
        assertThat(resumed.body().path("committed_rows").asInt()).isEqualTo(3);
        assertThat(count("select count(*) from transactions where tenant_id = :p0", p.space())).as("row A was not created a second time").isEqualTo(3);
        assertThat(balance(p, acc)).isEqualTo("-6.00");
    }

    @Test
    void cancellingBeforeAndDuringACommitIsHandledAndNothingElseHappensAfter() throws Exception {
        Person p = person();
        String acc = bank(p, "Main", "0.00");
        String id = ready(p, "Date,Amount\n2026-03-01,-1.00\n", acc, fields("date", "Date", "amount", "Amount"));
        Resp c = post(p, "/finance-imports/" + id + "/cancel", Map.of());
        assertThat(c.text("status")).isEqualTo("CANCELLED");
        assertThat(commit(p, id, "SKIP").status()).isEqualTo(409);
        assertThat(map(p, id, fields("date", "Date", "amount", "Amount"), Map.of()).status()).isEqualTo(409);
        assertThat(post(p, "/finance-imports/" + id + "/cancel", Map.of()).status()).as("already finished").isEqualTo(409);
        assertThat(count("select count(*) from transactions where tenant_id = :p0", p.space())).isZero();

        String running = ready(p, "Date,Amount\n2026-03-01,-1.00\n", acc, fields("date", "Date", "amount", "Amount"));
        jdbc.sql("update finance_imports set status = 'COMMITTING', possible_duplicate_policy = 'SKIP', lease_until = :l where id = :i").param("l", LIVE).param("i", UUID.fromString(running)).update();
        Resp req = post(p, "/finance-imports/" + running + "/cancel", Map.of());
        assertThat(req.text("status")).as("a running commit stops at its next batch").isEqualTo("COMMITTING");
        assertThat(count("select count(*) from finance_imports where id = :p0 and cancel_requested", UUID.fromString(running))).isEqualTo(1);
        String dead = ready(p, "Date,Amount\n2026-03-01,-1.00\n", acc, fields("date", "Date", "amount", "Amount"));
        jdbc.sql("update finance_imports set status = 'COMMITTING', possible_duplicate_policy = 'SKIP', lease_until = :l where id = :i").param("l", DEAD).param("i", UUID.fromString(dead)).update();
        assertThat(post(p, "/finance-imports/" + dead + "/cancel", Map.of()).text("status")).as("nobody is running it: close it now").isEqualTo("CANCELLED");
    }

    @Test
    void theDomainStillDecidesAtCommitTimeAndOneRefusedRowDoesNotStopTheOthers() throws Exception {
        Biz b = biz();
        String vendor = vendor(b, "Cement Co");
        ok(bpost(b, "/expense-categories", Map.of("name", "Materials")));
        String csv = "Date,Amount,Description,Vendor,Category,Bill\n"
                + "2026-03-01,100.00,Cement,Cement Co,Materials,B-1\n"
                + "2026-03-20,50.00,A date in the future,Cement Co,,B-2\n"
                + "2026-03-02,25.00,Unknown vendor,Nobody Ltd,,B-3\n"
                + "2026-03-03,-5.00,A negative expense,,,B-4\n"
                + "2026-03-04,60.00,Plain expense,,,B-5\n";
        Resp u = upBiz(b, "OWNER", b.owner(), "EXPENSES", "spend.csv", csv);
        assertThat(u.status()).as(u.raw().body()).isEqualTo(201);
        Resp m = as(b, b.owner(), "OWNER", "PUT", "/finance-imports/" + u.id() + "/mapping", Map.of("fields", fields("date", "Date", "amount", "Amount", "description", "Description", "vendor", "Vendor",
                "category", "Category", "reference", "Bill"), "options", Map.of()));
        assertThat(m.body().path("valid_rows").asInt()).isEqualTo(3);
        assertThat(m.body().path("invalid_rows").asInt()).isEqualTo(2);
        Resp done = as(b, b.owner(), "OWNER", "POST", "/finance-imports/" + u.id() + "/commit", Map.of("confirm", true, "possible_duplicates", "SKIP"));
        assertThat(done.status()).as(done.raw().body()).isEqualTo(200);
        assertThat(done.text("status")).isEqualTo("PARTIAL");
        assertThat(done.body().path("committed_rows").asInt()).isEqualTo(2);
        assertThat(done.body().path("rejected_rows").asInt()).isEqualTo(3);
        Resp rejected = as(b, b.owner(), "OWNER", "GET", "/finance-imports/" + u.id() + "/rows?status=REJECTED", null);
        assertThat(rejected.body().path("items")).hasSize(1);
        assertThat(rejected.body().path("items").get(0).path("errors").get(0).path("message").asString()).contains("future");
        assertThat(count("select count(*) from business_expenses where tenant_id = :p0", b.space())).isEqualTo(2);
        assertThat(count("select count(*) from finance_import_ledger where tenant_id = :p0", b.space())).as("a refused row leaves no ledger entry").isEqualTo(2);
        Resp first = bget(b, "/expenses?q=cement");
        assertThat(first.body().path("items").get(0).path("vendor_id").asString()).isEqualTo(vendor);
        assertThat(first.body().path("items").get(0).path("category_name").asString()).isEqualTo("Materials");
        assertThat(first.body().path("items").get(0).path("reference").asString()).isEqualTo("B-1");
    }

    @Test
    void europeanDatesAndDecimalCommasDebitCreditColumnsAndStatementLines() throws Exception {
        Person p = person();
        String acc = bank(p, "Main", "0.00");
        String csv = "Datum;Belastung;Gutschrift;Text;Beleg\n31/03/2026;1.250,75;;Rent;S-1\n01/03/2026;;2.000,00;Salary;S-2\n";
        Resp u = up(p, "STATEMENT", "statement.csv", csv, acc);
        assertThat(u.status()).as(u.raw().body()).isEqualTo(201);
        Resp bad = map(p, u.id(), fields("date", "Datum", "debit", "Belastung", "credit", "Gutschrift", "description", "Text", "reference", "Beleg"), Map.of());
        assertThat(bad.body().path("invalid_rows").asInt()).as("ISO dates and a decimal point are the defaults").isEqualTo(2);
        Resp ok = map(p, u.id(), fields("date", "Datum", "debit", "Belastung", "credit", "Gutschrift", "description", "Text", "reference", "Beleg"), Map.of("date_format", "DMY", "decimal_separator", ","));
        assertThat(ok.body().path("valid_rows").asInt()).isEqualTo(2);
        assertThat(commit(p, u.id(), "SKIP").text("status")).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from transactions where tenant_id = :p0", p.space())).as("a statement creates no financial records").isZero();
        assertThat(count("select count(*) from finance_statement_lines where tenant_id = :p0", p.space())).isEqualTo(2);
        assertThat(jdbc.sql("select amount::text from finance_statement_lines where tenant_id = :t and reference = 'S-1'").param("t", p.space()).query(String.class).single()).isEqualTo("-1250.7500");
        assertThat(jdbc.sql("select amount::text from finance_statement_lines where tenant_id = :t and reference = 'S-2'").param("t", p.space()).query(String.class).single()).isEqualTo("2000.0000");
        assertThat(jdbc.sql("select line_date::text from finance_statement_lines where tenant_id = :t and reference = 'S-1'").param("t", p.space()).query(String.class).single()).isEqualTo("2026-03-31");
    }

    @Test
    void unsupportedMalformedOversizedAndMisdirectedUploadsAreRefused() throws Exception {
        Person p = person();
        String acc = bank(p, "Main", "0.00");
        String base = base(p) + "/finance-imports";
        assertThat(upload(base + "?kind=TRANSACTIONS&filename=a.csv&account_id=" + acc, token(p), new byte[0], "text/csv").status()).as("empty").isEqualTo(422);
        Resp xlsx = upload(base + "?kind=TRANSACTIONS&filename=a.xlsx&account_id=" + acc, token(p), new byte[] {'P', 'K', 3, 4, 20, 0, 0, 0}, "application/octet-stream");
        assertThat(xlsx.status()).isEqualTo(422);
        assertThat(xlsx.raw().body()).contains("Excel").contains("CSV");
        assertThat(upload(base + "?kind=TRANSACTIONS&filename=a.csv&account_id=" + acc, token(p), new byte[] {'a', '\n', (byte) 0xC3, 0x28, '\n'}, "text/csv").status()).as("not UTF-8").isEqualTo(422);
        assertThat(upload(base + "?kind=TRANSACTIONS&filename=a.csv&account_id=" + acc, token(p), "Date,Amount\n".getBytes(), "text/csv").status()).as("no data rows").isEqualTo(422);
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        assertThat(upload(base + "?kind=TRANSACTIONS&filename=a.csv&account_id=" + acc, token(p), big, "text/csv").status()).as("oversized").isEqualTo(413);
        assertThat(up(p, "TRANSACTIONS", "a.csv", "Date,Amount\n2026-03-01,1\n", null).status()).as("a personal import needs an account").isEqualTo(422);
        assertThat(up(p, "TRANSACTIONS", "a.csv", "Date,Amount\n2026-03-01,1\n", UUID.randomUUID().toString()).status()).isEqualTo(422);
        assertThat(up(p, "EXPENSES", "a.csv", "Date,Amount\n2026-03-01,1\n", acc).status()).as("expenses belong to business workspaces").isEqualTo(422);
        assertThat(up(p, "NOPE", "a.csv", "Date,Amount\n2026-03-01,1\n", acc).status()).isEqualTo(422);
        assertThat(upload(base + "?kind=TRANSACTIONS&account_id=" + acc, token(p), "x".getBytes(), "text/csv").status()).as("filename is required").isEqualTo(400);
        ok(post(p, "/accounts/" + acc + "/archive", Map.of()));
        assertThat(up(p, "TRANSACTIONS", "a.csv", "Date,Amount\n2026-03-01,1\n", acc).status()).as("archived account").isEqualTo(422);
        Biz b = biz();
        assertThat(upBiz(b, "OWNER", b.owner(), "TRANSACTIONS", "a.csv", "Date,Amount\n2026-03-01,1\n").status()).as("transactions belong to personal profiles").isEqualTo(422);

        // filenames can never be paths
        Person q = person();
        String acc2 = bank(q, "Main", "0.00");
        Resp traversal = up(q, "TRANSACTIONS", "../../etc/passwd.csv", "Date,Amount\n2026-03-01,1\n", acc2);
        assertThat(traversal.status()).isEqualTo(201);
        assertThat(traversal.text("filename")).isEqualTo("passwd.csv");

        // mapping errors
        Resp u = up(q, "TRANSACTIONS", "m.csv", "Date,Amount,Memo\n2026-03-01,1,x\n", acc2);
        assertThat(map(q, u.id(), fields("date", "Date"), Map.of()).status()).as("amount is required").isEqualTo(422);
        assertThat(map(q, u.id(), fields("date", "Nope", "amount", "Amount"), Map.of()).status()).as("no such column").isEqualTo(422);
        assertThat(map(q, u.id(), fields("date", "Date", "amount", "Amount", "bogus", "Memo"), Map.of()).status()).isEqualTo(422);
        assertThat(map(q, u.id(), fields("date", "Date", "amount", "Amount"), Map.of("date_format", "XYZ")).status()).isEqualTo(422);
        assertThat(map(q, u.id(), fields("date", "Date", "amount", "Amount"), Map.of("decimal_separator", ";")).status()).isEqualTo(422);
        assertThat(map(q, u.id(), fields("date", "Date", "amount", "Amount"), Map.of("evil", "1")).status()).isEqualTo(422);
        assertThat(get(q, "/finance-imports/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(commit(q, u.id(), "SKIP").status()).as("not mapped yet").isEqualTo(409);
        assertThat(get(q, "/finance-imports/" + u.id() + "/rows?limit=0").status()).isEqualTo(422);
    }

    @Test
    void importsAreForTheRightRolesAndTheRightWorkspaceOnly() throws Exception {
        Biz b = biz();
        Biz other = biz();
        UUID user = UUID.randomUUID();
        String csv = "Date,Amount,Description\n2026-03-01,10.00,Spend\n";
        for (String role : List.of("ADMIN", "MANAGER", "CONTRIBUTOR", "AUDITOR", "VIEWER")) {
            assertThat(upBiz(b, role, user, "EXPENSES", "a.csv", csv).status()).as(role + " cannot upload").isEqualTo(403);
        }
        Resp u = upBiz(b, "ACCOUNTANT", user, "EXPENSES", "a.csv", csv);
        assertThat(u.status()).as(u.raw().body()).isEqualTo(201);
        String path = "/finance-imports/" + u.id();
        for (String role : List.of("ADMIN", "MANAGER", "CONTRIBUTOR", "VIEWER")) {
            assertThat(as(b, user, role, "GET", "/finance-imports", null).status()).as(role + " cannot list").isEqualTo(403);
            assertThat(as(b, user, role, "GET", path, null).status()).isEqualTo(403);
            assertThat(as(b, user, role, "PUT", path + "/mapping", Map.of("fields", Map.of())).status()).isEqualTo(403);
            assertThat(as(b, user, role, "POST", path + "/commit", Map.of("confirm", true, "possible_duplicates", "SKIP")).status()).isEqualTo(403);
            assertThat(as(b, user, role, "POST", path + "/cancel", Map.of()).status()).isEqualTo(403);
        }
        assertThat(as(b, user, "AUDITOR", "GET", "/finance-imports", null).status()).as("an Auditor may read").isEqualTo(200);
        assertThat(as(b, user, "AUDITOR", "GET", path + "/rows", null).status()).isEqualTo(200);
        assertThat(bytes("GET", "/v1/spaces/" + b.space() + path + "/error-report", btoken(b, user, "AUDITOR"), null, null).status()).isEqualTo(200);
        assertThat(as(b, user, "AUDITOR", "POST", path + "/commit", Map.of("confirm", true, "possible_duplicates", "SKIP")).status()).isEqualTo(403);
        // another workspace sees nothing and changes nothing
        assertThat(bget(other, "/finance-imports").body().path("items")).isEmpty();
        for (String suffix : List.of("", "/rows")) {
            assertThat(bget(other, path + suffix).status()).as(suffix).isEqualTo(404);
        }
        assertThat(bytes("GET", "/v1/spaces/" + other.space() + path + "/error-report", btoken(other, other.owner(), "OWNER"), null, null).status()).isEqualTo(404);
        assertThat(bput(other, path + "/mapping", Map.of("fields", Map.of("date", "Date"))).status()).isEqualTo(404);
        assertThat(bpost(other, path + "/commit", Map.of("confirm", true, "possible_duplicates", "SKIP")).status()).isEqualTo(404);
        assertThat(bpost(other, path + "/cancel", Map.of()).status()).isEqualTo(404);
        assertThat(bget(b, path).text("status")).isEqualTo("NEEDS_MAPPING");
        // a workspace-less or wrong-kind token never reaches the data
        assertThat(raw("GET", "/v1/spaces/" + b.space() + "/finance-imports", token(person(), b.space(), "OWNER", List.of("workspace:unknown")), null).status()).isEqualTo(409);
    }
}
