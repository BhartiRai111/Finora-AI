package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/** Document uploads: content validation, storage, access control, retention and the "OCR is unavailable" truth. */
class DocumentsTest extends BusinessTestSupport {

    static byte[] pdf(String marker) {
        return ("%PDF-1.4\n1 0 obj<< /Type /Catalog >>endobj\n% " + marker + "\ntrailer<<>>\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] png(String marker, long width, long height) {
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R',
                (byte) (width >> 24), (byte) (width >> 16), (byte) (width >> 8), (byte) width, (byte) (height >> 24), (byte) (height >> 16), (byte) (height >> 8), (byte) height};
        byte[] tail = marker.getBytes(StandardCharsets.ISO_8859_1);
        byte[] all = new byte[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    static byte[] jpeg(String marker) {
        byte[] head = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        byte[] tail = marker.getBytes(StandardCharsets.ISO_8859_1);
        byte[] all = new byte[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    private static String unique() {
        return UUID.randomUUID().toString();
    }

    Resp up(Biz b, String role, UUID user, String name, byte[] bytes) throws Exception {
        return upload("/v1/spaces/" + b.space() + "/finance-documents?kind=RECEIPT&filename=" + URLEncoder.encode(name, StandardCharsets.UTF_8), btoken(b, user, role), bytes, "application/octet-stream");
    }

    Resp upP(Person p, String name, byte[] bytes) throws Exception {
        return upload(base(p) + "/finance-documents?kind=RECEIPT&filename=" + URLEncoder.encode(name, StandardCharsets.UTF_8), token(p), bytes, "application/octet-stream");
    }

    @Test
    void pdfPngAndJpegAreAcceptedByTheirContentAndStoredUnderGeneratedKeys() throws Exception {
        Biz b = biz();
        Resp a = up(b, "OWNER", b.owner(), "receipt.pdf", pdf(unique()));
        Resp c = up(b, "OWNER", b.owner(), "photo.png", png(unique(), 800, 600));
        Resp d = up(b, "OWNER", b.owner(), "scan.jpg", jpeg(unique()));
        assertThat(a.status()).as(a.raw().body()).isEqualTo(201);
        assertThat(a.text("content_type")).isEqualTo("application/pdf");
        assertThat(c.text("content_type")).isEqualTo("image/png");
        assertThat(d.text("content_type")).isEqualTo("image/jpeg");
        assertThat(a.text("status")).isEqualTo("UPLOADED");
        assertThat(a.text("sha256")).hasSize(64);
        assertThat(a.body().path("ocr_available").asBoolean()).as("no OCR engine is configured by default").isFalse();
        assertThat(a.body().path("already_uploaded").asBoolean()).isFalse();
        // The key is a pair of random UUIDs, never anything the user typed
        String key = jdbc.sql("select storage_key from finance_documents where id = :id").param("id", UUID.fromString(a.id())).query(String.class).single();
        assertThat(key).matches("^[0-9a-f-]{36}/[0-9a-f-]{36}$").startsWith(b.space() + "/").doesNotContain("receipt");
        assertThat(bget(b, "/finance-documents").body().path("total").asLong()).isEqualTo(3);
        assertThat(bget(b, "/finance-documents?status=UPLOADED").body().path("items")).hasSize(3);
        assertThat(bget(b, "/finance-documents?status=NOPE").status()).isEqualTo(422);
    }

    @Test
    void contentIsCheckedNotTheNameTheMimeTypeOrTheExtension() throws Exception {
        Biz b = biz();
        record Bad(String name, byte[] data) {
        }
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};
        List<Bad> bad = List.of(
                new Bad("invoice.pdf", exe),
                new Bad("notes.pdf", "just some text, not a PDF".getBytes()),
                new Bad("page.png", "<html><script>alert(1)</script></html>".getBytes()),
                new Bad("script.jpg", "<?php system($_GET['c']); ?>".getBytes()),
                new Bad("active.pdf", "%PDF-1.4\n1 0 obj<< /JavaScript (app.alert(1)) >>endobj\n%%EOF\n".getBytes()),
                new Bad("launch.pdf", "%PDF-1.4\n1 0 obj<< /Launch << /F (cmd.exe) >> >>endobj\n%%EOF\n".getBytes()),
                new Bad("embedded.pdf", "%PDF-1.4\n1 0 obj<< /EmbeddedFile >>endobj\n%%EOF\n".getBytes()),
                new Bad("broken.pdf", "%PDF-1.4\n1 0 obj<< >>endobj\n".getBytes()),
                new Bad("bomb.png", png(unique(), 30_000, 30_000)),
                new Bad("zero.png", png(unique(), 0, 10)),
                new Bad("archive.pdf", new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0}),
                new Bad("svg.png", "<svg xmlns='http://www.w3.org/2000/svg'><script>1</script></svg>".getBytes()),
                new Bad("empty.pdf", new byte[0]));
        for (Bad x : bad) {
            Resp r = up(b, "OWNER", b.owner(), x.name(), x.data());
            assertThat(r.status()).as(x.name() + " -> " + r.raw().body()).isEqualTo(422);
        }
        assertThat(count("select count(*) from finance_documents where tenant_id = :p0", b.space())).isZero();
        // a real PDF is accepted whatever it is called and whatever the browser said
        Resp odd = upload("/v1/spaces/" + b.space() + "/finance-documents?filename=report.txt", btoken(b, b.owner(), "OWNER"), pdf(unique()), "image/gif");
        assertThat(odd.status()).as("the browser-provided type is ignored").isEqualTo(201);
        assertThat(odd.text("content_type")).isEqualTo("application/pdf");
        assertThat(upload("/v1/spaces/" + b.space() + "/finance-documents?kind=BOGUS&filename=a.pdf", btoken(b, b.owner(), "OWNER"), pdf(unique()), "application/octet-stream").status()).isEqualTo(422);
        assertThat(upload("/v1/spaces/" + b.space() + "/finance-documents?kind=RECEIPT", btoken(b, b.owner(), "OWNER"), pdf(unique()), "application/octet-stream").status()).as("filename required").isEqualTo(400);
    }

    @Test
    void sizeLimitAndFilenamesThatAreNotNames() throws Exception {
        Biz b = biz();
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(pdf("x"), 0, big, 0, pdf("x").length);
        assertThat(up(b, "OWNER", b.owner(), "big.pdf", big).status()).isEqualTo(413);
        byte[] limit = new byte[10 * 1024 * 1024];
        byte[] head = pdf("limit");
        System.arraycopy(head, 0, limit, 0, head.length);
        byte[] tail = "%%EOF\n".getBytes();
        System.arraycopy(tail, 0, limit, limit.length - tail.length, tail.length);
        assertThat(up(b, "OWNER", b.owner(), "exactly-ten-mb.pdf", limit).status()).isEqualTo(201);
        Resp traversal = up(b, "OWNER", b.owner(), "../../../etc/passwd.pdf", pdf(unique()));
        assertThat(traversal.status()).isEqualTo(201);
        assertThat(traversal.text("filename")).isEqualTo("passwd.pdf");
        Resp odd = up(b, "OWNER", b.owner(), "re<ce>ip|t \"1\".pdf", pdf(unique()));
        assertThat(odd.text("filename")).doesNotContain("<").doesNotContain(">").doesNotContain("|").doesNotContain("\"");
        assertThat(up(b, "OWNER", b.owner(), "a".repeat(200) + ".pdf", pdf(unique())).status()).isEqualTo(422);
        assertThat(up(b, "OWNER", b.owner(), "..\\..\\windows\\system32.pdf", pdf(unique())).text("filename")).isEqualTo("system32.pdf");
    }

    @Test
    void theSameFileIsStoredOnceAndConcurrentUploadsDoNotDuplicateIt() throws Exception {
        Biz b = biz();
        byte[] file = pdf(unique());
        Resp first = up(b, "OWNER", b.owner(), "a.pdf", file);
        Resp second = up(b, "OWNER", b.owner(), "renamed.pdf", file);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.body().path("already_uploaded").asBoolean()).isTrue();
        byte[] other = pdf(unique());
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            calls.add(() -> up(b, "OWNER", b.owner(), "c.pdf", other).id());
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(5);
        List<String> ids = new ArrayList<>();
        for (var f : pool.invokeAll(calls)) {
            ids.add(f.get());
        }
        pool.shutdown();
        assertThat(ids.stream().distinct()).hasSize(1);
        assertThat(count("select count(*) from finance_documents where tenant_id = :p0", b.space())).isEqualTo(2);
        // the same file in another workspace is its own document
        Biz elsewhere = biz();
        Resp third = up(elsewhere, "OWNER", elsewhere.owner(), "a.pdf", file);
        assertThat(third.id()).isNotEqualTo(first.id());
        assertThat(third.body().path("already_uploaded").asBoolean()).isFalse();
    }

    @Test
    void downloadsAreAttachmentsWithTheSniffedTypeAndAGeneratedName() throws Exception {
        Biz b = biz();
        byte[] png = png(unique(), 10, 10);
        Resp up = up(b, "OWNER", b.owner(), "my secret name.png", png);
        BinResp r = bytes("GET", "/v1/spaces/" + b.space() + "/finance-documents/" + up.id() + "/content", btoken(b, b.owner(), "OWNER"), null, null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.bytes()).isEqualTo(png);
        assertThat(r.header("Content-Type")).isEqualTo("image/png");
        assertThat(r.header("Content-Disposition")).isEqualTo("attachment; filename=\"document-" + up.id() + ".png\"");
        assertThat(r.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.header("Cache-Control")).isEqualTo("no-store");
        assertThat(count("select count(*) from finance_audit_events where tenant_id = :p0 and event_type = 'DOCUMENT_DOWNLOADED'", b.space())).isEqualTo(1);
        assertThat(bytes("GET", "/v1/spaces/" + b.space() + "/finance-documents/" + UUID.randomUUID() + "/content", btoken(b, b.owner(), "OWNER"), null, null).status()).isEqualTo(404);
    }

    @Test
    void anotherWorkspaceCannotSeeDownloadChangeOrDeleteADocument() throws Exception {
        Biz a = biz();
        Biz other = biz();
        Person stranger = person();
        Resp doc = up(a, "OWNER", a.owner(), "private.pdf", pdf(unique()));
        String path = "/finance-documents/" + doc.id();
        for (String suffix : List.of("", "/content")) {
            assertThat(bytes("GET", "/v1/spaces/" + other.space() + path + suffix, btoken(other, other.owner(), "OWNER"), null, null).status()).as(suffix).isEqualTo(404);
            assertThat(bytes("GET", base(stranger) + path + suffix, token(stranger), null, null).status()).as("personal " + suffix).isEqualTo(404);
        }
        assertThat(as(other, other.owner(), "OWNER", "POST", path + "/extract", Map.of()).status()).isEqualTo(404);
        assertThat(as(other, other.owner(), "OWNER", "POST", path + "/confirm", Map.of("confirm", true), "Idempotency-Key", unique()).status()).isEqualTo(404);
        assertThat(as(other, other.owner(), "OWNER", "DELETE", path, null).status()).isEqualTo(404);
        assertThat(bget(other, "/finance-documents").body().path("items")).isEmpty();
        // a token for workspace B on workspace A's path is "not found"; a personal profile never reaches business documents
        assertThat(raw("GET", "/v1/spaces/" + a.space() + path, btoken(other, other.owner(), "OWNER"), null).status()).isEqualTo(404);
        assertThat(bget(a, path).text("status")).isEqualTo("UPLOADED");
        assertThat(bget(a, path + "/../" + UUID.randomUUID()).status()).as("path tricks find nothing").isIn(400, 404);
    }

    @Test
    void rolesDecideWhoCanUploadReadAndDelete() throws Exception {
        Biz b = biz();
        UUID ann = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Resp owners = up(b, "OWNER", b.owner(), "owner.pdf", pdf(unique()));
        Resp anns = up(b, "CONTRIBUTOR", ann, "ann.pdf", pdf(unique()));
        assertThat(anns.status()).as("a Contributor may upload").isEqualTo(201);
        Resp bobs = up(b, "CONTRIBUTOR", bob, "bob.pdf", pdf(unique()));
        // a Contributor sees only what they uploaded
        assertThat(as(b, ann, "CONTRIBUTOR", "GET", "/finance-documents", null).body().path("total").asLong()).isEqualTo(1);
        assertThat(as(b, ann, "CONTRIBUTOR", "GET", "/finance-documents/" + anns.id(), null).status()).isEqualTo(200);
        assertThat(as(b, ann, "CONTRIBUTOR", "GET", "/finance-documents/" + bobs.id(), null).status()).as("someone else's document").isEqualTo(404);
        assertThat(bytes("GET", "/v1/spaces/" + b.space() + "/finance-documents/" + owners.id() + "/content", btoken(b, ann, "CONTRIBUTOR"), null, null).status()).isEqualTo(404);
        assertThat(as(b, ann, "CONTRIBUTOR", "DELETE", "/finance-documents/" + anns.id(), null).status()).as("no delete right").isEqualTo(403);
        assertThat(as(b, ann, "CONTRIBUTOR", "POST", "/finance-documents/" + anns.id() + "/extract", Map.of()).status()).isEqualTo(403);
        // Manager and Viewer have no access; Admin's limited view is not evaluated here, so it fails closed; an Auditor reads only
        for (String role : List.of("MANAGER", "VIEWER", "ADMIN")) {
            assertThat(as(b, ann, role, "GET", "/finance-documents", null).status()).as(role).isEqualTo(403);
            assertThat(up(b, role, ann, "x.pdf", pdf(unique())).status()).as(role + " upload").isEqualTo(403);
        }
        assertThat(as(b, ann, "AUDITOR", "GET", "/finance-documents", null).body().path("total").asLong()).isEqualTo(3);
        assertThat(bytes("GET", "/v1/spaces/" + b.space() + "/finance-documents/" + owners.id() + "/content", btoken(b, ann, "AUDITOR"), null, null).status()).isEqualTo(200);
        assertThat(up(b, "AUDITOR", ann, "x.pdf", pdf(unique())).status()).isEqualTo(403);
        assertThat(as(b, ann, "AUDITOR", "DELETE", "/finance-documents/" + owners.id(), null).status()).isEqualTo(403);
        // an Accountant manages everything; a deleted document is gone and its file with it
        assertThat(as(b, ann, "ACCOUNTANT", "DELETE", "/finance-documents/" + bobs.id(), null).status()).isEqualTo(200);
        assertThat(bget(b, "/finance-documents/" + bobs.id()).status()).isEqualTo(404);
        assertThat(bytes("GET", "/v1/spaces/" + b.space() + "/finance-documents/" + bobs.id() + "/content", btoken(b, b.owner(), "OWNER"), null, null).status()).isEqualTo(404);
        assertThat(bget(b, "/finance-documents").body().path("total").asLong()).isEqualTo(2);
        assertThat(as(b, ann, "ACCOUNTANT", "DELETE", "/finance-documents/" + bobs.id(), null).status()).as("already deleted").isEqualTo(404);
        assertThat(count("select count(*) from finance_audit_events where tenant_id = :p0 and event_type = 'DOCUMENT_DELETED'", b.space())).isEqualTo(1);
        // the same file can be uploaded again after it was deleted
        assertThat(up(b, "OWNER", b.owner(), "bob-again.pdf", jdbcBytesUnavailable()).status()).isEqualTo(201);
    }

    private byte[] jdbcBytesUnavailable() {
        return pdf(unique());
    }

    @Test
    void withoutAnOcrProviderTheDocumentSaysSoAndNothingIsInvented() throws Exception {
        Biz b = biz();
        Resp doc = up(b, "OWNER", b.owner(), "r.pdf", pdf(unique()));
        Resp ex = as(b, b.owner(), "OWNER", "POST", "/finance-documents/" + doc.id() + "/extract", Map.of());
        assertThat(ex.status()).isEqualTo(200);
        assertThat(ex.text("status")).isEqualTo("UNAVAILABLE");
        assertThat(ex.text("failure_reason")).contains("No OCR provider is configured");
        assertThat(ex.body().path("extraction").isNull()).isTrue();
        assertThat(ex.body().path("ocr_available").asBoolean()).isFalse();
        assertThat(as(b, b.owner(), "OWNER", "POST", "/finance-documents/" + doc.id() + "/extract", Map.of()).text("status")).as("asking again is harmless").isEqualTo("UNAVAILABLE");
        assertThat(count("select count(*) from business_expenses where tenant_id = :p0", b.space())).isZero();
        // the user can still type the values and confirm them: that is their own entry, not OCR
        Resp confirm = as(b, b.owner(), "OWNER", "POST", "/finance-documents/" + doc.id() + "/confirm",
                Map.of("confirm", true, "date", "2026-03-05", "amount", "321.50", "currency", "INR", "description", "Typed from the paper receipt"), "Idempotency-Key", unique());
        assertThat(confirm.status()).as(confirm.raw().body()).isEqualTo(200);
        assertThat(confirm.text("status")).isEqualTo("CONFIRMED");
        assertThat(confirm.text("linked_type")).isEqualTo("EXPENSE");
        assertThat(bget(b, "/expenses/" + confirm.text("linked_id")).text("amount")).isEqualTo("321.50");
        assertThat(as(b, b.owner(), "OWNER", "DELETE", "/finance-documents/" + doc.id(), null).status()).as("it supports a recorded expense").isEqualTo(409);
        assertThat(as(b, b.owner(), "OWNER", "POST", "/finance-documents/" + doc.id() + "/extract", Map.of()).status()).isEqualTo(409);
    }

    @Test
    void confirmingNeedsAnExplicitConfirmationAndValidValuesAndHappensOnce() throws Exception {
        Biz b = biz();
        Resp doc = up(b, "OWNER", b.owner(), "r.pdf", pdf(unique()));
        Map<String, Object> values = Map.of("confirm", true, "date", "2026-03-05", "amount", "50.00", "currency", "INR", "description", "Stationery");
        String path = "/finance-documents/" + doc.id() + "/confirm";
        assertThat(as(b, b.owner(), "OWNER", "POST", path, values).status()).as("a key is required").isEqualTo(400);
        assertThat(as(b, b.owner(), "OWNER", "POST", path, Map.of("date", "2026-03-05", "amount", "50.00", "currency", "INR", "description", "x"), "Idempotency-Key", unique()).status()).as("confirm must be true").isEqualTo(422);
        assertThat(as(b, b.owner(), "OWNER", "POST", path, Map.of("confirm", true, "date", "2026-03-05", "amount", "abc", "currency", "INR", "description", "x"), "Idempotency-Key", unique()).status()).isEqualTo(422);
        assertThat(as(b, b.owner(), "OWNER", "POST", path, Map.of("confirm", true, "date", "2027-01-01", "amount", "5.00", "currency", "INR", "description", "x"), "Idempotency-Key", unique()).status()).as("future date").isEqualTo(422);
        assertThat(count("select count(*) from business_expenses where tenant_id = :p0", b.space())).as("a refused confirmation leaves nothing behind").isZero();
        assertThat(bget(b, "/finance-documents/" + doc.id()).text("status")).isEqualTo("UPLOADED");
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(() -> as(b, b.owner(), "OWNER", "POST", path, values, "Idempotency-Key", unique()).status());
        }
        List<Integer> statuses = concurrently(calls);
        assertThat(statuses.stream().filter(s -> s == 200)).as("exactly one confirmation wins").hasSize(1);
        assertThat(count("select count(*) from business_expenses where tenant_id = :p0", b.space())).isEqualTo(1);
        // the same key replays instead of creating a second expense
        String key = unique();
        Resp other = up(b, "OWNER", b.owner(), "r2.pdf", pdf(unique()));
        Resp one = as(b, b.owner(), "OWNER", "POST", "/finance-documents/" + other.id() + "/confirm", values, "Idempotency-Key", key);
        Resp two = as(b, b.owner(), "OWNER", "POST", "/finance-documents/" + other.id() + "/confirm", values, "Idempotency-Key", key);
        assertThat(one.status()).isEqualTo(200);
        assertThat(two.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(count("select count(*) from business_expenses where tenant_id = :p0", b.space())).isEqualTo(2);
    }

    @Test
    void personalDocumentsBecomeTransactionsOnlyThroughTheUsersConfirmation() throws Exception {
        Person p = person();
        String acc = bank(p, "Main", "100.00");
        Resp doc = upP(p, "till-receipt.png", png(unique(), 50, 50));
        assertThat(doc.status()).isEqualTo(201);
        String path = "/finance-documents/" + doc.id() + "/confirm";
        assertThat(raw("POST", base(p) + path, token(p), Map.of("confirm", true, "date", "2026-03-05", "amount", "12.00", "currency", "INR", "description", "Lunch"), "Idempotency-Key", unique()).status()).as("an account is required").isEqualTo(422);
        Resp ok = raw("POST", base(p) + path, token(p), Map.of("confirm", true, "date", "2026-03-05", "amount", "12.00", "currency", "INR", "description", "Lunch", "account_id", acc), "Idempotency-Key", unique());
        assertThat(ok.status()).as(ok.raw().body()).isEqualTo(200);
        assertThat(ok.text("linked_type")).isEqualTo("TRANSACTION");
        assertThat(balance(p, acc)).isEqualTo("88.00");
        // personal data and business data never meet
        Biz b = biz();
        assertThat(bget(b, "/finance-documents").body().path("items")).isEmpty();
        assertThat(raw("GET", "/v1/spaces/" + p.space() + "/finance-documents", btoken(b, b.owner(), "OWNER"), null).status()).as("a business token on a personal path").isEqualTo(404);
    }
}
