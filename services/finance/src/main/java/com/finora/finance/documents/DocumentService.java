package com.finora.finance.documents;

import com.finora.commons.authz.Action;
import com.finora.commons.authz.Scope;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.business.ExpenseService;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.Validate;
import com.finora.finance.insights.InsightsContext;
import com.finora.finance.money.Currencies;
import com.finora.finance.transactions.TransactionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Receipts, invoices and other supporting files. Uploads are validated by their CONTENT (magic bytes), never by file name or the browser's
 * MIME type; only PDF, PNG and JPEG are accepted, up to 10 MB, stored under generated keys outside any served directory. Text read by OCR is
 * untrusted: it is sanitised, shown to the user as candidates, and a financial record is created only by the user's explicit confirm with the
 * values they reviewed. Without a configured OCR provider the document says so (UNAVAILABLE) and the user can still enter the values by hand.
 */
@Service
public class DocumentService {

    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Set<String> KINDS = Set.of("RECEIPT", "INVOICE", "OTHER");
    private static final Pattern AMOUNT = Pattern.compile("^\\d{1,12}(\\.\\d{1,4})?$");
    private static final int PROCESSING_TIMEOUT_SECONDS = 300;

    public record Extraction(String provider, Map<String, String> fields, List<String> warnings, boolean untrusted) {
    }

    public record Doc(UUID id, String kind, String filename, String contentType, long sizeBytes, String sha256, String status, String failureReason, Extraction extraction,
            String linkedType, UUID linkedId, boolean ocrAvailable, boolean alreadyUploaded, OffsetDateTime uploadedAt) {
    }

    public record Page(List<Doc> items, long total, int limit, int offset) {
    }

    public record Blob(byte[] bytes, String contentType, String extension) {
    }

    public record ConfirmRequest(Boolean confirm, String kind, String date, String amount, String currency, String description, String reference, String vendorId,
            String categoryId, String projectId, String accountId) {
    }

    private record Row(UUID id, String kind, String originalFilename, String contentType, long sizeBytes, String sha256, String storageKey, String status, String failureReason,
            String extraction, OffsetDateTime processingStartedAt, String linkedType, UUID linkedId, UUID createdBy, OffsetDateTime createdAt) {
    }

    private static final String COLS = """
            id, kind, original_filename, content_type, size_bytes, sha256, storage_key, status, failure_reason, extraction, processing_started_at, linked_type, linked_id, created_by, created_at
            """;

    private final JdbcClient jdbc;
    private final DocumentStorage storage;
    private final OcrProvider ocr;
    private final ObjectMapper json;
    private final FinanceAudit audit;
    private final ExpenseService expenses;
    private final TransactionService transactions;

    public DocumentService(JdbcClient jdbc, DocumentStorage storage, OcrProvider ocr, ObjectMapper json, FinanceAudit audit, ExpenseService expenses, TransactionService transactions) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.ocr = ocr;
        this.json = json;
        this.audit = audit;
        this.expenses = expenses;
        this.transactions = transactions;
    }

    private static OffsetDateTime now(InsightsContext ctx) {
        return OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ content validation

    record Sniffed(String contentType, String extension) {
    }

    static Sniffed sniff(byte[] b) {
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            String text = new String(b, StandardCharsets.ISO_8859_1);
            for (String active : new String[] {"/JavaScript", "/JS ", "/JS(", "/Launch", "/EmbeddedFile", "/OpenAction"}) {
                if (text.contains(active)) {
                    throw Validate.invalid("This PDF contains active content (scripts, launch actions or embedded files) and was not accepted");
                }
            }
            if (!text.substring(Math.max(0, text.length() - 2048)).contains("%%EOF")) {
                throw Validate.invalid("The PDF file looks damaged or incomplete");
            }
            return new Sniffed("application/pdf", "pdf");
        }
        if (b.length >= 24 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            long w = ((b[16] & 0xffL) << 24) | ((b[17] & 0xffL) << 16) | ((b[18] & 0xffL) << 8) | (b[19] & 0xffL);
            long h = ((b[20] & 0xffL) << 24) | ((b[21] & 0xffL) << 16) | ((b[22] & 0xffL) << 8) | (b[23] & 0xffL);
            if (w == 0 || h == 0 || w > 20_000 || h > 20_000 || w * h > 100_000_000L) {
                throw Validate.invalid("The image dimensions are not acceptable");
            }
            return new Sniffed("image/png", "png");
        }
        if (b.length >= 4 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) {
            return new Sniffed("image/jpeg", "jpg");
        }
        throw Validate.invalid("Only PDF, PNG and JPEG files are accepted (the file content is checked, not its name)");
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String displayName(String raw) {
        String v = raw == null ? "" : raw.trim();
        int slash = Math.max(v.lastIndexOf('/'), v.lastIndexOf('\\'));
        if (slash >= 0) {
            v = v.substring(slash + 1);
        }
        v = v.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (v.isBlank() || v.length() > 120) {
            throw Validate.invalid("filename is required (up to 120 characters)");
        }
        return v;
    }

    // ------------------------------------------------------------------ queries

    private Row row(InsightsContext ctx, UUID id, Scope scope, boolean lock) {
        Row r = jdbc.sql("select " + COLS + " from finance_documents where tenant_id = :t and id = :id and deleted_at is null" + (lock ? " for update" : ""))
                .param("t", ctx.tenantId()).param("id", id).query(Row.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
        if (scope == Scope.OWN && !r.createdBy().equals(ctx.userId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, null);
        }
        return r;
    }

    private Doc view(Row r, boolean already) {
        Extraction ex = r.extraction() == null ? null : json.readValue(r.extraction(), Extraction.class);
        return new Doc(r.id(), r.kind(), r.originalFilename(), r.contentType(), r.sizeBytes(), r.sha256(), r.status(), r.failureReason(), ex, r.linkedType(), r.linkedId(),
                ocr.configured(), already, r.createdAt());
    }

    public Page list(InsightsContext ctx, String status, Integer limit, Integer offset) {
        Scope scope = ctx.documents(Action.READ);
        int lim = limit == null ? 20 : limit;
        int off = offset == null ? 0 : offset;
        if (lim < 1 || lim > 100 || off < 0) {
            throw Validate.invalid("limit must be 1-100 and offset 0 or more");
        }
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (st != null && !Set.of("UPLOADED", "PROCESSING", "EXTRACTED", "FAILED", "UNAVAILABLE", "CONFIRMED").contains(st)) {
            throw Validate.invalid("status is not a valid choice");
        }
        String where = "tenant_id = :t and deleted_at is null" + (scope == Scope.OWN ? " and created_by = :u" : "") + (st == null ? "" : " and status = :s");
        var count = jdbc.sql("select count(*) from finance_documents where " + where).param("t", ctx.tenantId());
        var query = jdbc.sql("select " + COLS + " from finance_documents where " + where + " order by created_at desc, id limit :l offset :o").param("t", ctx.tenantId()).param("l", lim).param("o", off);
        if (scope == Scope.OWN) {
            count = count.param("u", ctx.userId());
            query = query.param("u", ctx.userId());
        }
        if (st != null) {
            count = count.param("s", st);
            query = query.param("s", st);
        }
        return new Page(query.query(Row.class).list().stream().map(r -> view(r, false)).toList(), count.query(Long.class).single(), lim, off);
    }

    public Doc get(InsightsContext ctx, UUID id) {
        return view(row(ctx, id, ctx.documents(Action.READ), false), false);
    }

    public Blob download(InsightsContext ctx, UUID id) {
        Row r = row(ctx, id, ctx.documents(Action.READ), false);
        byte[] bytes = storage.load(ctx.tenantId(), r.storageKey());
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "DOCUMENT_DOWNLOADED", "DOCUMENT", id, null);
        return new Blob(bytes, r.contentType(), r.contentType().equals("application/pdf") ? "pdf" : r.contentType().equals("image/png") ? "png" : "jpg");
    }

    // ------------------------------------------------------------------ writes

    public Doc upload(InsightsContext ctx, String kindRaw, String filename, byte[] bytes) {
        Scope scope = ctx.documents(Action.CREATE);
        String kind = kindRaw == null ? "OTHER" : kindRaw.trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw Validate.invalid("kind must be RECEIPT, INVOICE or OTHER");
        }
        if (bytes == null || bytes.length == 0) {
            throw Validate.invalid("The file is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The file is larger than " + (MAX_BYTES / 1024 / 1024) + " MB");
        }
        String name = displayName(filename);
        Sniffed type = sniff(bytes);
        String hash = sha256(bytes);
        var existing = jdbc.sql("select " + COLS + " from finance_documents where tenant_id = :t and sha256 = :h and deleted_at is null").param("t", ctx.tenantId()).param("h", hash)
                .query(Row.class).optional();
        if (existing.isPresent()) {
            if (scope == Scope.OWN && !existing.get().createdBy().equals(ctx.userId())) {
                throw new ApiException(ErrorCode.CONFLICT, "This file is already stored in the workspace");
            }
            return view(existing.get(), true);
        }
        String key = storage.store(ctx.tenantId(), bytes);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into finance_documents (id, tenant_id, domain, kind, original_filename, content_type, size_bytes, sha256, storage_key, created_by, created_at, updated_at)
                    values (:id, :t, :d, :k, :f, :ct, :s, :h, :key, :u, :now, :now)
                    """).param("id", id).param("t", ctx.tenantId()).param("d", ctx.domain().name()).param("k", kind).param("f", name).param("ct", type.contentType()).param("s", (long) bytes.length)
                    .param("h", hash).param("key", key).param("u", ctx.userId()).param("now", now(ctx)).update();
        } catch (DuplicateKeyException e) {
            storage.delete(ctx.tenantId(), key); // a concurrent upload of the same file won
            Row winner = jdbc.sql("select " + COLS + " from finance_documents where tenant_id = :t and sha256 = :h and deleted_at is null").param("t", ctx.tenantId()).param("h", hash)
                    .query(Row.class).single();
            return view(winner, true);
        } catch (RuntimeException e) {
            storage.delete(ctx.tenantId(), key);
            throw e;
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "DOCUMENT_UPLOADED", "DOCUMENT", id, kind + " " + type.contentType() + " " + bytes.length + " bytes");
        return view(row(ctx, id, Scope.ALL, false), false);
    }

    public Doc delete(InsightsContext ctx, UUID id) {
        Scope scope = ctx.documents(Action.ARCHIVE);
        Row r = row(ctx, id, scope, true);
        if (r.linkedId() != null) {
            throw new ApiException(ErrorCode.CONFLICT, "This document supports a recorded " + r.linkedType().toLowerCase(Locale.ROOT) + " and cannot be deleted");
        }
        jdbc.sql("update finance_documents set deleted_at = :now, deleted_by = :u, status = case when status = 'PROCESSING' then 'FAILED' else status end, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                .param("now", now(ctx)).param("u", ctx.userId()).param("t", ctx.tenantId()).param("id", id).update();
        storage.delete(ctx.tenantId(), r.storageKey());
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "DOCUMENT_DELETED", "DOCUMENT", id, null);
        return view(r, false);
    }

    /** Runs OCR on the document (or reports that no provider is configured). Only one run can claim the document at a time. */
    public Doc extract(InsightsContext ctx, UUID id) {
        Scope scope = ctx.documents(Action.UPDATE);
        Row r = row(ctx, id, scope, false);
        OffsetDateTime now = now(ctx);
        if (!ocr.configured()) {
            int n = jdbc.sql("update finance_documents set status = 'UNAVAILABLE', failure_reason = 'No OCR provider is configured, so nothing was read from this document. You can enter the values yourself.', updated_at = :now where tenant_id = :t and id = :id and status in ('UPLOADED', 'FAILED', 'UNAVAILABLE')")
                    .param("now", now).param("t", ctx.tenantId()).param("id", id).update();
            if (n == 0) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This document is " + r.status().toLowerCase(Locale.ROOT) + " and cannot be processed");
            }
            return view(row(ctx, id, scope, false), false);
        }
        int claimed = jdbc.sql("""
                update finance_documents set status = 'PROCESSING', processing_started_at = :now, failure_reason = null, updated_at = :now
                where tenant_id = :t and id = :id and deleted_at is null and (status in ('UPLOADED', 'FAILED', 'UNAVAILABLE') or (status = 'PROCESSING' and processing_started_at < :stale))
                """).param("now", now).param("stale", now.minusSeconds(PROCESSING_TIMEOUT_SECONDS)).param("t", ctx.tenantId()).param("id", id).update();
        if (claimed == 0) {
            Row again = row(ctx, id, scope, false);
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, again.status().equals("PROCESSING") ? "This document is already being processed"
                    : "This document is " + again.status().toLowerCase(Locale.ROOT) + " and cannot be processed again");
        }
        String status;
        String reason = null;
        String extraction = null;
        try {
            byte[] bytes = storage.load(ctx.tenantId(), r.storageKey());
            OcrProvider.Result result = ocr.extract(bytes, r.contentType());
            extraction = json.writeValueAsString(sanitize(result));
            status = "EXTRACTED";
        } catch (OcrProvider.OcrException e) {
            status = "FAILED";
            reason = e.getMessage();
        } catch (RuntimeException e) {
            status = "FAILED";
            reason = "The document could not be processed";
        }
        jdbc.sql("update finance_documents set status = :s, failure_reason = :r, extraction = :x, processing_started_at = null, updated_at = :now where tenant_id = :t and id = :id and status = 'PROCESSING'")
                .param("s", status).param("r", reason).param("x", extraction).param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "DOCUMENT_" + status, "DOCUMENT", id, null);
        return view(row(ctx, id, scope, false), false);
    }

    /** OCR output is untrusted: only known keys, each checked for shape and length, control characters removed. */
    Extraction sanitize(OcrProvider.Result result) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, String> e : result.fields().entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim().toLowerCase(Locale.ROOT);
            String value = e.getValue() == null ? "" : e.getValue().replaceAll("\\p{Cntrl}", " ").trim();
            if (value.isEmpty()) {
                continue;
            }
            switch (key) {
                case "vendor_name", "description", "reference" -> {
                    int max = key.equals("description") ? 300 : key.equals("reference") ? 60 : 120;
                    if (value.length() > max) {
                        warnings.add(key + " was too long and was dropped");
                    } else {
                        out.put(key, value);
                    }
                }
                case "document_date" -> {
                    try {
                        out.put(key, LocalDate.parse(value).toString());
                    } catch (RuntimeException ex) {
                        warnings.add("document_date was not a valid date and was dropped");
                    }
                }
                case "total_amount" -> {
                    if (AMOUNT.matcher(value).matches()) {
                        out.put(key, value);
                    } else {
                        warnings.add("total_amount was not a plain number and was dropped");
                    }
                }
                case "currency" -> {
                    String c = value.toUpperCase(Locale.ROOT);
                    if (Currencies.supported(c)) {
                        out.put(key, c);
                    } else {
                        warnings.add("currency was not supported and was dropped");
                    }
                }
                default -> warnings.add("an unknown field was ignored");
            }
        }
        return new Extraction(ocr.name(), out, warnings, true);
    }

    /**
     * The user's confirmation: creates ONE financial record from the values the user reviewed (typed or corrected in the request), through the
     * existing domain service, and links the document to it. The stored OCR candidates are never used here.
     */
    @Transactional
    public Doc confirm(InsightsContext ctx, UUID id, ConfirmRequest req) {
        Scope scope = ctx.documents(Action.UPDATE);
        Row r = row(ctx, id, scope, true);
        if (!Boolean.TRUE.equals(req.confirm())) {
            throw Validate.invalid("confirm must be true: review the values and confirm them");
        }
        if (r.status().equals("CONFIRMED")) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This document already supports a recorded " + r.linkedType().toLowerCase(Locale.ROOT));
        }
        if (r.status().equals("PROCESSING")) {
            throw new ApiException(ErrorCode.CONFLICT, "This document is still being processed");
        }
        UUID recordId;
        String type;
        if (ctx.business()) {
            type = "EXPENSE";
            recordId = expenses.create(ctx.businessContext(), new ExpenseService.Request(req.date(), req.amount(), req.currency(), req.vendorId(), null, req.projectId(), null,
                    req.categoryId(), null, req.description(), req.reference(), null)).id();
        } else {
            type = "TRANSACTION";
            String kind = req.kind() == null ? "EXPENSE" : req.kind().trim().toUpperCase(Locale.ROOT);
            if (!kind.equals("EXPENSE") && !kind.equals("INCOME")) {
                throw Validate.invalid("kind must be EXPENSE or INCOME");
            }
            recordId = transactions.create(ctx.personal(), new TransactionService.CreateRequest(kind, req.accountId(), req.amount(), req.currency(), req.date(), req.categoryId(),
                    req.description(), req.reference(), null, null)).id();
        }
        jdbc.sql("update finance_documents set status = 'CONFIRMED', linked_type = :lt, linked_id = :lid, failure_reason = null, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                .param("lt", type).param("lid", recordId).param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "DOCUMENT_CONFIRMED", "DOCUMENT", id, type + " " + recordId);
        return view(row(ctx, id, scope, false), false);
    }
}
