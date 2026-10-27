package com.finora.finance.imports;

import com.finora.commons.authz.Action;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.business.ExpenseService;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.Validate;
import com.finora.finance.insights.Csv;
import com.finora.finance.insights.InsightsContext;
import com.finora.finance.money.Amounts;
import com.finora.finance.money.Currencies;
import com.finora.finance.transactions.TransactionService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * CSV imports into the existing personal or business finance domain. Workflow: upload (parsed and stored row by row) -> map columns ->
 * preview (every row validated and classified, nothing written) -> explicit commit -> history. Financial records are created ONLY through
 * the existing domain services (so every domain rule and permission check applies), one row per transaction, so a failure affects that row
 * only and the job reports exact counts. Statement imports create statement lines for reconciliation, not financial records.
 *
 * <p>Duplicates: a stable source identifier (external id) is unique per workspace and kind (database-enforced, so retries and concurrent
 * commits cannot create a record twice) and such rows are always skipped as DUPLICATE. Rows without an id are matched only by content and
 * are merely flagged POSSIBLE_DUPLICATE: they are imported or skipped only on the user's explicit choice at commit, and two identical rows
 * in the same file are both legitimate. Commit is claimed with a conditional update and a lease, so only one worker runs it, an
 * interrupted commit resumes where it stopped (committed rows are never repeated) and several application instances are safe.
 */
@Service
public class ImportService {

    public static final Set<String> KINDS = Set.of("STATEMENT", "TRANSACTIONS", "EXPENSES");
    static final Map<String, List<String>> REQUIRED = Map.of("STATEMENT", List.of("date"), "TRANSACTIONS", List.of("date", "amount"), "EXPENSES", List.of("date", "amount", "description"));
    static final Map<String, List<String>> OPTIONAL = Map.of(
            "STATEMENT", List.of("amount", "debit", "credit", "description", "reference", "external_id", "currency"),
            "TRANSACTIONS", List.of("type", "description", "category", "reference", "external_id", "currency"),
            "EXPENSES", List.of("vendor", "category", "reference", "external_id", "currency"));
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("date", List.of("date", "txndate", "transactiondate", "valuedate", "postingdate", "expensedate", "bookingdate")),
            Map.entry("amount", List.of("amount", "amt", "value", "total", "transactionamount")),
            Map.entry("debit", List.of("debit", "withdrawal", "withdrawals", "paidout", "dr")),
            Map.entry("credit", List.of("credit", "deposit", "deposits", "paidin", "cr")),
            Map.entry("description", List.of("description", "details", "narration", "memo", "particulars", "payee", "name")),
            Map.entry("reference", List.of("reference", "ref", "refno", "chequeno", "utr", "billno")),
            Map.entry("external_id", List.of("id", "externalid", "transactionid", "txnid", "uniqueid", "referenceid")),
            Map.entry("currency", List.of("currency", "ccy", "cur")),
            Map.entry("type", List.of("type", "kind", "drcr", "creditdebit")),
            Map.entry("category", List.of("category", "head", "expensecategory")),
            Map.entry("vendor", List.of("vendor", "supplier", "payeename")));
    private static final Pattern PLAIN = Pattern.compile("^-?\\d{1,15}(\\.\\d+)?$");
    private static final int LEASE_SECONDS = 300;
    private static final int BATCH = 200;

    public record ErrorItem(String field, String message) {
    }

    public record Job(UUID id, String kind, String status, String filename, UUID accountId, String currency, int totalRows, int validRows, int invalidRows,
            int duplicateRows, int possibleDuplicateRows, int committedRows, int skippedRows, int rejectedRows, String possibleDuplicatePolicy,
            List<String> headers, Map<String, String> mapping, Map<String, String> options, List<String> requiredFields, List<String> optionalFields,
            Map<String, String> suggestedMapping, List<List<String>> sample, OffsetDateTime createdAt, OffsetDateTime completedAt) {
    }

    public record RowView(int rowNumber, String status, List<ErrorItem> errors, List<String> raw, Map<String, String> normalized, String possibleDuplicateOf,
            UUID recordId) {
    }

    public record MappingRequest(Map<String, String> fields, Map<String, String> options) {
    }

    public record CommitRequest(Boolean confirm, String possibleDuplicates) {
    }

    public record Page<T>(List<T> items, long total, int limit, int offset) {
    }

    record Config(List<String> headers, Map<String, String> fields, Map<String, String> options) {
    }

    private record JobRow(UUID id, String kind, String status, String filename, UUID accountId, String currency, String mapping, int totalRows, int validRows,
            int invalidRows, int duplicateRows, int possibleDuplicateRows, int committedRows, int skippedRows, int rejectedRows, String possibleDuplicatePolicy,
            OffsetDateTime leaseUntil, boolean cancelRequested, OffsetDateTime createdAt, OffsetDateTime completedAt) {
    }

    private record RawRow(UUID id, int rowNumber, String raw, String status, String errors, String normalized, String possibleDuplicateOf, UUID recordId,
            String externalId, String dedupeKey) {
    }

    private record Norm(Map<String, String> values, List<ErrorItem> errors) {
    }

    private static final String JOB_COLS = """
            id, kind, status, filename, account_id, currency, mapping, total_rows, valid_rows, invalid_rows, duplicate_rows, possible_duplicate_rows, committed_rows,
            skipped_rows, rejected_rows, possible_duplicate_policy, lease_until, cancel_requested, created_at, completed_at
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final ObjectMapper json;
    private final FinanceAudit audit;
    private final TransactionService transactions;
    private final ExpenseService expenses;
    private final TransactionTemplate rowTx;
    private final com.finora.finance.notifications.NotificationService notifications;

    public ImportService(JdbcClient jdbc, JdbcTemplate template, ObjectMapper json, FinanceAudit audit, TransactionService transactions, ExpenseService expenses,
            PlatformTransactionManager tm, com.finora.finance.notifications.NotificationService notifications) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.template = template;
        this.json = json;
        this.audit = audit;
        this.transactions = transactions;
        this.expenses = expenses;
        this.rowTx = new TransactionTemplate(tm);
        this.rowTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ helpers

    private static OffsetDateTime now(InsightsContext ctx) {
        return OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC);
    }

    private String write(Object o) {
        return json.writeValueAsString(o);
    }

    private <T> T read(String s, TypeReference<T> type) {
        return json.readValue(s, type);
    }

    private JobRow job(InsightsContext ctx, UUID id) {
        return job(ctx, id, false);
    }

    private JobRow job(InsightsContext ctx, UUID id, boolean lock) {
        return jdbc.sql("select " + JOB_COLS + " from finance_imports where tenant_id = :t and id = :id" + (lock ? " for update" : "")).param("t", ctx.tenantId()).param("id", id)
                .query(JobRow.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    private Config config(JobRow j) {
        if (j.mapping() == null) {
            return null;
        }
        return json.readValue(j.mapping(), Config.class);
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    static String safeFilename(String raw) {
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

    public Page<Job> list(InsightsContext ctx, Integer limit, Integer offset) {
        ctx.requireImports(Action.READ);
        int lim = limit == null ? 20 : limit;
        int off = offset == null ? 0 : offset;
        if (lim < 1 || lim > 100 || off < 0) {
            throw Validate.invalid("limit must be 1-100 and offset 0 or more");
        }
        long total = jdbc.sql("select count(*) from finance_imports where tenant_id = :t").param("t", ctx.tenantId()).query(Long.class).single();
        List<Job> items = jdbc.sql("select " + JOB_COLS + " from finance_imports where tenant_id = :t order by created_at desc, id limit :l offset :o")
                .param("t", ctx.tenantId()).param("l", lim).param("o", off).query(JobRow.class).list().stream().map(j -> view(ctx, j, false)).toList();
        return new Page<>(items, total, lim, off);
    }

    public Job get(InsightsContext ctx, UUID id) {
        ctx.requireImports(Action.READ);
        return view(ctx, job(ctx, id), true);
    }

    private Job view(InsightsContext ctx, JobRow j, boolean detail) {
        Config c = config(j);
        List<String> headers = c == null ? List.of() : c.headers();
        Map<String, String> suggested = null;
        List<List<String>> sample = null;
        if (detail && c != null) {
            if ("NEEDS_MAPPING".equals(j.status())) {
                suggested = suggest(j.kind(), headers);
            }
            sample = jdbc.sql("select raw from finance_import_rows where tenant_id = :t and import_id = :i order by row_number limit 5").param("t", ctx.tenantId()).param("i", j.id())
                    .query(String.class).list().stream().map(s -> read(s, new TypeReference<List<String>>() { })).toList();
        }
        return new Job(j.id(), j.kind(), j.status(), j.filename(), j.accountId(), j.currency() == null ? null : j.currency().trim(), j.totalRows(), j.validRows(), j.invalidRows(),
                j.duplicateRows(), j.possibleDuplicateRows(), j.committedRows(), j.skippedRows(), j.rejectedRows(), j.possibleDuplicatePolicy(), headers,
                c == null ? Map.of() : c.fields(), c == null ? Map.of() : c.options(), REQUIRED.get(j.kind()), OPTIONAL.get(j.kind()), suggested, sample, j.createdAt(), j.completedAt());
    }

    public Page<RowView> rows(InsightsContext ctx, UUID id, String status, Integer limit, Integer offset) {
        ctx.requireImports(Action.READ);
        JobRow j = job(ctx, id);
        int lim = limit == null ? 50 : limit;
        int off = offset == null ? 0 : offset;
        if (lim < 1 || lim > 200 || off < 0) {
            throw Validate.invalid("limit must be 1-200 and offset 0 or more");
        }
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        String where = "tenant_id = :t and import_id = :i" + (st == null ? "" : " and status = :s");
        var count = jdbc.sql("select count(*) from finance_import_rows where " + where).param("t", ctx.tenantId()).param("i", j.id());
        var query = jdbc.sql("select id, row_number, raw, status, errors, normalized, possible_duplicate_of, record_id, external_id, dedupe_key from finance_import_rows where " + where
                + " order by row_number limit :l offset :o").param("t", ctx.tenantId()).param("i", j.id()).param("l", lim).param("o", off);
        if (st != null) {
            count = count.param("s", st);
            query = query.param("s", st);
        }
        List<RowView> items = query.query(RawRow.class).list().stream().map(r -> new RowView(r.rowNumber(), r.status(),
                r.errors() == null ? List.of() : read(r.errors(), new TypeReference<List<ErrorItem>>() { }), read(r.raw(), new TypeReference<List<String>>() { }),
                r.normalized() == null ? Map.of() : read(r.normalized(), new TypeReference<Map<String, String>>() { }), r.possibleDuplicateOf(), r.recordId())).toList();
        return new Page<>(items, count.query(Long.class).single(), lim, off);
    }

    /** CSV of the rows that did not make it (invalid, rejected, duplicate, skipped), with the original values and the reason. */
    public String errorReport(InsightsContext ctx, UUID id) {
        ctx.requireImports(Action.READ);
        JobRow j = job(ctx, id);
        Config c = config(j);
        List<String> headers = c == null ? List.of() : c.headers();
        StringBuilder out = new StringBuilder();
        List<String> head = new ArrayList<>(List.of("row_number", "status", "problems"));
        head.addAll(headers);
        Csv.row(out, head);
        for (RawRow r : jdbc.sql("""
                select id, row_number, raw, status, errors, normalized, possible_duplicate_of, record_id, external_id, dedupe_key from finance_import_rows
                where tenant_id = :t and import_id = :i and status in ('INVALID', 'REJECTED', 'DUPLICATE', 'SKIPPED') order by row_number
                """).param("t", ctx.tenantId()).param("i", j.id()).query(RawRow.class).list()) {
            String problems = r.errors() == null ? "" : read(r.errors(), new TypeReference<List<ErrorItem>>() { }).stream()
                    .map(e -> (e.field() == null ? "" : e.field() + ": ") + e.message()).reduce((a, b) -> a + " | " + b).orElse("");
            if (problems.isEmpty() && r.possibleDuplicateOf() != null) {
                problems = r.possibleDuplicateOf();
            }
            List<String> cells = new ArrayList<>(List.of(String.valueOf(r.rowNumber()), r.status(), problems));
            cells.addAll(read(r.raw(), new TypeReference<List<String>>() { }));
            Csv.row(out, cells);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ upload

    @org.springframework.transaction.annotation.Transactional
    public Job upload(InsightsContext ctx, String kindRaw, String filenameRaw, UUID accountId, String currencyRaw, byte[] bytes) {
        ctx.requireImports(Action.CREATE);
        String kind = kindRaw == null ? "" : kindRaw.trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw Validate.invalid("kind must be STATEMENT, TRANSACTIONS or EXPENSES");
        }
        if (ctx.business() && kind.equals("TRANSACTIONS")) {
            throw Validate.invalid("TRANSACTIONS imports are for personal profiles; business workspaces import EXPENSES or STATEMENT files");
        }
        if (!ctx.business() && kind.equals("EXPENSES")) {
            throw Validate.invalid("EXPENSES imports are for business workspaces; personal profiles import TRANSACTIONS or STATEMENT files");
        }
        String filename = safeFilename(filenameRaw);
        String currency = currencyRaw == null || currencyRaw.isBlank() ? null : currencyRaw.trim().toUpperCase(Locale.ROOT);
        if (currency != null && !Currencies.supported(currency)) {
            throw Validate.invalid("currency is not supported");
        }
        UUID account = null;
        if (!ctx.business() && !kind.equals("EXPENSES")) {
            if (accountId == null) {
                throw Validate.invalid("account_id is required: choose the account this file belongs to");
            }
            String accountCurrency = jdbc.sql("select currency from accounts where tenant_id = :t and id = :id and archived_at is null").param("t", ctx.tenantId()).param("id", accountId)
                    .query(String.class).optional().orElseThrow(() -> Validate.invalid("account_id does not refer to an active account"));
            account = accountId;
            currency = accountCurrency.trim();
        }
        if (ctx.business() && currency == null) {
            currency = jdbc.sql("select base_currency from business_settings where tenant_id = :t").param("t", ctx.tenantId()).query(String.class).optional().orElse("INR").trim();
        }
        CsvParser.Parsed parsed = CsvParser.parse(bytes);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now(ctx);
        Config cfg = new Config(parsed.headers(), Map.of(), Map.of());
        jdbc.sql("""
                insert into finance_imports (id, tenant_id, domain, kind, status, filename, sha256, size_bytes, account_id, currency, mapping, total_rows, created_by, created_at, updated_at)
                values (:id, :t, :d, :k, 'NEEDS_MAPPING', :f, :h, :s, :a, :c, :m, :n, :u, :now, :now)
                """).param("id", id).param("t", ctx.tenantId()).param("d", ctx.domain().name()).param("k", kind).param("f", filename).param("h", sha256(bytes)).param("s", (long) bytes.length)
                .param("a", account).param("c", currency).param("m", write(cfg)).param("n", parsed.rows().size()).param("u", ctx.userId()).param("now", now).update();
        List<Object[]> batch = new ArrayList<>();
        int n = 1;
        for (List<String> row : parsed.rows()) {
            batch.add(new Object[] {UUID.randomUUID(), ctx.tenantId(), id, n++, write(row)});
        }
        template.batchUpdate("insert into finance_import_rows (id, tenant_id, import_id, row_number, raw) values (?, ?, ?, ?, ?)", batch);
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "IMPORT_UPLOADED", "IMPORT", id, kind + " " + parsed.rows().size() + " rows");
        return view(ctx, job(ctx, id), true);
    }

    static Map<String, String> suggest(String kind, List<String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> fields = new ArrayList<>(REQUIRED.get(kind));
        fields.addAll(OPTIONAL.get(kind));
        for (String field : fields) {
            for (String h : headers) {
                String norm = h.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (SYNONYMS.getOrDefault(field, List.of()).contains(norm) && !out.containsValue(h)) {
                    out.put(field, h);
                    break;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ mapping and preview

    private Map<String, Map<String, UUID>> lookups(InsightsContext ctx, String kind) {
        Map<String, Map<String, UUID>> out = new HashMap<>();
        Map<String, UUID> cats = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        String catSql = ctx.business() ? "select lower(name) as n, id from expense_categories where tenant_id = :t and archived_at is null"
                : "select lower(name) as n, id from categories where tenant_id = :t and archived_at is null";
        jdbc.sql(catSql).param("t", ctx.tenantId()).query((rs, i) -> new Object[] {rs.getString(1), rs.getObject(2, UUID.class)}).list().forEach(o -> {
            if (cats.put((String) o[0], (UUID) o[1]) != null) {
                ambiguous.add((String) o[0]);
            }
        });
        ambiguous.forEach(cats::remove);
        out.put("categories", cats);
        Map<String, UUID> amb = new HashMap<>();
        ambiguous.forEach(a -> amb.put(a, null));
        out.put("ambiguous_categories", amb);
        if (ctx.business() && kind.equals("EXPENSES")) {
            Map<String, UUID> vendors = new HashMap<>();
            jdbc.sql("select lower(name), id from vendors where tenant_id = :t and archived_at is null").param("t", ctx.tenantId())
                    .query((rs, i) -> new Object[] {rs.getString(1), rs.getObject(2, UUID.class)}).list().forEach(o -> vendors.put((String) o[0], (UUID) o[1]));
            out.put("vendors", vendors);
        }
        return out;
    }

    /** Runs under a row lock on the job, so it cannot interleave with a commit that is claiming the same job. */
    @org.springframework.transaction.annotation.Transactional
    public Job map(InsightsContext ctx, UUID id, MappingRequest req) {
        ctx.requireImports(Action.UPDATE);
        JobRow j = job(ctx, id, true);
        if (!"NEEDS_MAPPING".equals(j.status()) && !"READY".equals(j.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This import can no longer be changed (" + j.status().toLowerCase(Locale.ROOT) + ")");
        }
        Config old = config(j);
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> allowed = new ArrayList<>(REQUIRED.get(j.kind()));
        allowed.addAll(OPTIONAL.get(j.kind()));
        Map<String, String> requested = req.fields() == null ? Map.of() : req.fields();
        for (Map.Entry<String, String> e : requested.entrySet()) {
            if (!allowed.contains(e.getKey())) {
                throw Validate.invalid("'" + e.getKey() + "' is not a field of a " + j.kind() + " import");
            }
            if (e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            if (!old.headers().contains(e.getValue())) {
                throw Validate.invalid("The file has no column named '" + e.getValue() + "'");
            }
            fields.put(e.getKey(), e.getValue());
        }
        for (String r : REQUIRED.get(j.kind())) {
            if (!fields.containsKey(r)) {
                throw Validate.invalid("Choose the column for '" + r + "'");
            }
        }
        if (j.kind().equals("STATEMENT") && !fields.containsKey("amount") && !fields.containsKey("debit") && !fields.containsKey("credit")) {
            throw Validate.invalid("Choose the amount column, or the debit and credit columns");
        }
        if (fields.containsKey("amount") && (fields.containsKey("debit") || fields.containsKey("credit"))) {
            throw Validate.invalid("Use either an amount column or debit/credit columns, not both");
        }
        Map<String, String> options = new LinkedHashMap<>();
        Map<String, String> opts = req.options() == null ? Map.of() : req.options();
        String dateFormat = opts.getOrDefault("date_format", "ISO").toUpperCase(Locale.ROOT);
        if (!Set.of("ISO", "DMY", "MDY").contains(dateFormat)) {
            throw Validate.invalid("date_format must be ISO, DMY or MDY");
        }
        String decimal = opts.getOrDefault("decimal_separator", ".");
        if (!Set.of(".", ",").contains(decimal)) {
            throw Validate.invalid("decimal_separator must be . or ,");
        }
        options.put("date_format", dateFormat);
        options.put("decimal_separator", decimal);
        for (String key : opts.keySet()) {
            if (!options.containsKey(key)) {
                throw Validate.invalid("'" + key + "' is not a known option");
            }
        }
        Config cfg = new Config(old.headers(), fields, options);
        classify(ctx, j, cfg);
        jdbc.sql("update finance_imports set mapping = :m, status = 'READY', updated_at = :now where tenant_id = :t and id = :id").param("m", write(cfg)).param("now", now(ctx))
                .param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "IMPORT_MAPPED", "IMPORT", id, null);
        return view(ctx, job(ctx, id), true);
    }

    private LocalDate date(String raw, String format) {
        String[] p = raw.trim().split("[/.\\-]");
        try {
            int a;
            int b;
            int c;
            if (format.equals("ISO")) {
                if (!raw.trim().matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                    throw new IllegalArgumentException();
                }
                return LocalDate.parse(raw.trim());
            }
            if (p.length != 3 || p[2].length() != 4) {
                throw new IllegalArgumentException();
            }
            a = Integer.parseInt(p[0]);
            b = Integer.parseInt(p[1]);
            c = Integer.parseInt(p[2]);
            LocalDate d = format.equals("DMY") ? LocalDate.of(c, b, a) : LocalDate.of(c, a, b);
            if (d.getYear() < 2000 || d.getYear() > 2100) {
                throw new IllegalArgumentException();
            }
            return d;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(format.equals("ISO") ? "use the format 2026-03-31" : format.equals("DMY") ? "use the format 31/03/2026 (day first)" : "use the format 03/31/2026 (month first)");
        }
    }

    private BigDecimal amount(String raw, String decimal, String currency, boolean allowNegative) {
        String s = raw.trim();
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1).trim();
        }
        if (!s.matches("^[+-]?[0-9][0-9., ]*$")) {
            throw new IllegalArgumentException("amount must be a number such as 1250.50");
        }
        if (s.startsWith("-")) {
            negative = !negative;
            s = s.substring(1);
        } else if (s.startsWith("+")) {
            s = s.substring(1);
        }
        s = decimal.equals(",") ? s.replace(".", "").replace(" ", "").replace(",", ".") : s.replace(",", "").replace(" ", "");
        if (!PLAIN.matcher(s).matches()) {
            throw new IllegalArgumentException("amount must be a number such as 1250.50 (check the decimal separator setting)");
        }
        BigDecimal v;
        try {
            v = Amounts.parse("amount", (negative ? "-" : "") + s, currency, Amounts.Sign.ANY);
        } catch (ApiException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        if (v.signum() < 0 && !allowNegative) {
            throw new IllegalArgumentException("a negative amount is not allowed here");
        }
        return v;
    }

    private Norm normalize(InsightsContext ctx, JobRow j, Config cfg, List<String> raw, Map<String, Map<String, UUID>> lookups) {
        Map<String, String> v = new LinkedHashMap<>();
        List<ErrorItem> errors = new ArrayList<>();
        if (raw.size() != cfg.headers().size()) {
            errors.add(new ErrorItem(null, "This row has " + raw.size() + " columns but the header has " + cfg.headers().size()));
            return new Norm(v, errors);
        }
        java.util.function.Function<String, String> cell = field -> {
            String header = cfg.fields().get(field);
            return header == null ? null : raw.get(cfg.headers().indexOf(header)).trim();
        };
        String currency = j.currency() == null ? null : j.currency().trim();
        String curCell = cell.apply("currency");
        if (curCell != null && !curCell.isEmpty()) {
            String c = curCell.toUpperCase(Locale.ROOT);
            if (!Currencies.supported(c)) {
                errors.add(new ErrorItem("currency", "'" + trunc(curCell) + "' is not a supported currency"));
            } else if (j.accountId() != null && !c.equals(currency)) {
                errors.add(new ErrorItem("currency", "The row is in " + c + " but the account is in " + currency));
            } else {
                currency = c;
            }
        }
        v.put("currency", currency);
        String dateText = cell.apply("date");
        LocalDate date = null;
        if (dateText == null || dateText.isEmpty()) {
            errors.add(new ErrorItem("date", "The date is missing"));
        } else {
            try {
                date = date(dateText, cfg.options().get("date_format"));
                v.put("date", date.toString());
            } catch (IllegalArgumentException e) {
                errors.add(new ErrorItem("date", "'" + trunc(dateText) + "' is not a valid date; " + e.getMessage()));
            }
        }
        String dec = cfg.options().get("decimal_separator");
        BigDecimal amount = null;
        boolean statement = j.kind().equals("STATEMENT");
        if (currency != null && Currencies.supported(currency)) {
            String amountText = cell.apply("amount");
            if (cfg.fields().containsKey("amount")) {
                if (amountText == null || amountText.isEmpty()) {
                    errors.add(new ErrorItem("amount", "The amount is missing"));
                } else {
                    try {
                        amount = amount(amountText, dec, currency, statement || j.kind().equals("TRANSACTIONS"));
                    } catch (IllegalArgumentException e) {
                        errors.add(new ErrorItem("amount", e.getMessage()));
                    }
                }
            } else if (statement) {
                String debit = cell.apply("debit");
                String credit = cell.apply("credit");
                boolean hasDebit = debit != null && !debit.isEmpty();
                boolean hasCredit = credit != null && !credit.isEmpty();
                if (hasDebit == hasCredit) {
                    errors.add(new ErrorItem("amount", hasDebit ? "Both debit and credit are filled; one is expected" : "Neither debit nor credit is filled"));
                } else {
                    try {
                        BigDecimal x = amount(hasDebit ? debit : credit, dec, currency, false);
                        amount = hasDebit ? x.negate() : x;
                    } catch (IllegalArgumentException e) {
                        errors.add(new ErrorItem(hasDebit ? "debit" : "credit", e.getMessage()));
                    }
                }
            }
        }
        if (amount != null && amount.signum() == 0) {
            errors.add(new ErrorItem("amount", "The amount cannot be zero"));
            amount = null;
        }
        if (amount != null) {
            v.put("amount", amount.toPlainString());
        }
        String description = cell.apply("description");
        if (description != null && description.length() > 300) {
            errors.add(new ErrorItem("description", "The description is longer than 300 characters"));
        }
        if (j.kind().equals("EXPENSES") && (description == null || description.isEmpty())) {
            errors.add(new ErrorItem("description", "The description is missing"));
        }
        v.put("description", description == null || description.isEmpty() ? null : description);
        String reference = cell.apply("reference");
        if (reference != null && reference.length() > 60) {
            errors.add(new ErrorItem("reference", "The reference is longer than 60 characters"));
        }
        v.put("reference", reference == null || reference.isEmpty() ? null : reference);
        String ext = cell.apply("external_id");
        if (ext != null && ext.length() > 100) {
            errors.add(new ErrorItem("external_id", "The id is longer than 100 characters"));
        }
        v.put("external_id", ext == null || ext.isEmpty() ? null : ext);
        if (j.kind().equals("TRANSACTIONS") && amount != null) {
            String type = cell.apply("type");
            String kind;
            if (type != null && !type.isEmpty()) {
                String t = type.toUpperCase(Locale.ROOT);
                if (Set.of("INCOME", "CREDIT", "CR", "IN", "DEPOSIT").contains(t)) {
                    kind = "INCOME";
                } else if (Set.of("EXPENSE", "DEBIT", "DR", "OUT", "WITHDRAWAL").contains(t)) {
                    kind = "EXPENSE";
                } else {
                    kind = null;
                    errors.add(new ErrorItem("type", "'" + trunc(type) + "' is not income or expense"));
                }
                if (kind != null && amount.signum() < 0) {
                    errors.add(new ErrorItem("amount", "Use a positive amount together with a type column"));
                }
            } else {
                kind = amount.signum() < 0 ? "EXPENSE" : "INCOME";
            }
            if (kind != null) {
                v.put("kind", kind);
                v.put("amount", amount.abs().toPlainString());
            }
        }
        String category = cell.apply("category");
        if (category != null && !category.isEmpty()) {
            String key = category.toLowerCase(Locale.ROOT);
            if (lookups.get("ambiguous_categories").containsKey(key)) {
                errors.add(new ErrorItem("category", "More than one category is named '" + trunc(category) + "'"));
            } else if (!lookups.get("categories").containsKey(key)) {
                errors.add(new ErrorItem("category", "There is no category named '" + trunc(category) + "'. Create it first; imports never create categories"));
            } else {
                v.put("category_id", lookups.get("categories").get(key).toString());
            }
        }
        String vendor = cell.apply("vendor");
        if (vendor != null && !vendor.isEmpty()) {
            UUID vid = lookups.getOrDefault("vendors", Map.of()).get(vendor.toLowerCase(Locale.ROOT));
            if (vid == null) {
                errors.add(new ErrorItem("vendor", "There is no active vendor named '" + trunc(vendor) + "'. Create it first; imports never create vendors"));
            } else {
                v.put("vendor_id", vid.toString());
            }
        }
        return new Norm(v, errors);
    }

    private static String trunc(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static String naturalBase(JobRow j, Map<String, String> v) {
        return sha256(String.join("|", j.kind(), String.valueOf(j.accountId()), String.valueOf(v.get("date")), String.valueOf(v.get("amount")), String.valueOf(v.get("currency")),
                String.valueOf(v.get("kind")), String.valueOf(v.get("description")).toLowerCase(Locale.ROOT), String.valueOf(v.get("reference")).toLowerCase(Locale.ROOT)));
    }

    private void classify(InsightsContext ctx, JobRow j, Config cfg) {
        Map<String, Map<String, UUID>> lookups = lookups(ctx, j.kind());
        List<RawRow> rows = jdbc.sql("select id, row_number, raw, status, errors, normalized, possible_duplicate_of, record_id, external_id, dedupe_key from finance_import_rows where tenant_id = :t and import_id = :i order by row_number")
                .param("t", ctx.tenantId()).param("i", j.id()).query(RawRow.class).list();
        Map<Integer, Norm> norms = new HashMap<>();
        Map<String, Integer> ordinals = new HashMap<>();
        Map<Integer, String> external = new HashMap<>();
        Map<Integer, String> natural = new HashMap<>();
        Set<String> seenExternal = new HashSet<>();
        LocalDate min = null;
        LocalDate max = null;
        for (RawRow r : rows) {
            Norm n = normalize(ctx, j, cfg, read(r.raw(), new TypeReference<List<String>>() { }), lookups);
            norms.put(r.rowNumber(), n);
            if (!n.errors().isEmpty()) {
                continue;
            }
            LocalDate d = LocalDate.parse(n.values().get("date"));
            min = min == null || d.isBefore(min) ? d : min;
            max = max == null || d.isAfter(max) ? d : max;
            String ext = n.values().get("external_id");
            if (ext != null) {
                external.put(r.rowNumber(), (j.accountId() == null ? "" : j.accountId() + "|") + ext.toLowerCase(Locale.ROOT));
            } else {
                String base = naturalBase(j, n.values());
                int ord = ordinals.merge(base, 1, Integer::sum);
                natural.put(r.rowNumber(), base + "#" + ord);
            }
        }
        Set<String> knownExternal = ledgerKeys(ctx, j.kind(), "EXTERNAL", new ArrayList<>(external.values()));
        Map<String, String> knownNatural = ledgerNatural(ctx, j.kind(), new ArrayList<>(natural.values()));
        Set<String> existing = min == null ? Set.of() : existingRecords(ctx, j, min, max);
        int valid = 0;
        int invalid = 0;
        int dup = 0;
        int possible = 0;
        List<Object[]> updates = new ArrayList<>();
        for (RawRow r : rows) {
            Norm n = norms.get(r.rowNumber());
            String status;
            String pdup = null;
            String dedupe = null;
            String extId = n.values().get("external_id");
            if (!n.errors().isEmpty()) {
                status = "INVALID";
                invalid++;
            } else if (external.containsKey(r.rowNumber())) {
                dedupe = external.get(r.rowNumber());
                if (knownExternal.contains(dedupe)) {
                    status = "DUPLICATE";
                    pdup = "This id was already imported";
                    dup++;
                } else if (!seenExternal.add(dedupe)) {
                    status = "DUPLICATE";
                    pdup = "This id appears earlier in this file";
                    dup++;
                } else {
                    status = "VALID";
                    valid++;
                }
            } else {
                dedupe = natural.get(r.rowNumber());
                String prior = knownNatural.get(dedupe);
                String content = n.values().get("date") + "|" + new BigDecimal(n.values().get("amount")).setScale(4).toPlainString() + "|" + n.values().get("currency") + (j.kind().equals("TRANSACTIONS") ? "|" + n.values().get("kind") : "");
                if (prior != null) {
                    status = "POSSIBLE_DUPLICATE";
                    pdup = "An identical row was already imported (" + prior + ")";
                    possible++;
                } else if (existing.contains(content)) {
                    status = "POSSIBLE_DUPLICATE";
                    pdup = "An existing record has the same date and amount";
                    possible++;
                } else {
                    status = "VALID";
                    valid++;
                }
            }
            updates.add(new Object[] {status, n.errors().isEmpty() ? null : write(n.errors()), write(n.values()), extId == null ? null : extId, dedupe, pdup, r.id()});
        }
        template.batchUpdate("update finance_import_rows set status = ?, errors = ?, normalized = ?, external_id = ?, dedupe_key = ?, possible_duplicate_of = ? where id = ?", updates);
        jdbc.sql("""
                update finance_imports set valid_rows = :v, invalid_rows = :i, duplicate_rows = :d, possible_duplicate_rows = :p, rejected_rows = :i, total_rows = :n where tenant_id = :t and id = :id
                """).param("v", valid).param("i", invalid).param("d", dup).param("p", possible).param("n", rows.size()).param("t", ctx.tenantId()).param("id", j.id()).update();
    }

    private Set<String> ledgerKeys(InsightsContext ctx, String kind, String type, List<String> keys) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < keys.size(); i += 500) {
            List<String> chunk = keys.subList(i, Math.min(keys.size(), i + 500));
            out.addAll(jdbc.sql("select dedupe_key from finance_import_ledger where tenant_id = :t and kind = :k and key_type = :ty and dedupe_key in (:keys)").param("t", ctx.tenantId())
                    .param("k", kind).param("ty", type).param("keys", chunk).query(String.class).list());
        }
        return out;
    }

    private Map<String, String> ledgerNatural(InsightsContext ctx, String kind, List<String> keys) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < keys.size(); i += 500) {
            List<String> chunk = keys.subList(i, Math.min(keys.size(), i + 500));
            jdbc.sql("select dedupe_key, row_number, import_id from finance_import_ledger where tenant_id = :t and kind = :k and key_type = 'NATURAL' and dedupe_key in (:keys)")
                    .param("t", ctx.tenantId()).param("k", kind).param("keys", chunk).query((rs, n) -> new Object[] {rs.getString(1), rs.getInt(2), rs.getObject(3, UUID.class)}).list()
                    .forEach(o -> out.putIfAbsent((String) o[0], "row " + o[1] + " of import " + o[2]));
        }
        return out;
    }

    private Set<String> existingRecords(InsightsContext ctx, JobRow j, LocalDate min, LocalDate max) {
        Set<String> out = new HashSet<>();
        if (j.kind().equals("TRANSACTIONS")) {
            jdbc.sql("select occurred_on, amount, currency, kind from transactions where tenant_id = :t and account_id = :a and status = 'POSTED' and kind in ('INCOME', 'EXPENSE') and occurred_on between :f and :to")
                    .param("t", ctx.tenantId()).param("a", j.accountId()).param("f", min).param("to", max)
                    .query((rs, i) -> rs.getDate(1).toLocalDate() + "|" + rs.getBigDecimal(2).setScale(4).toPlainString() + "|" + rs.getString(3).trim() + "|" + rs.getString(4)).list().forEach(out::add);
        } else if (j.kind().equals("EXPENSES")) {
            jdbc.sql("select expense_date, amount, currency from business_expenses where tenant_id = :t and status = 'POSTED' and expense_date between :f and :to")
                    .param("t", ctx.tenantId()).param("f", min).param("to", max)
                    .query((rs, i) -> rs.getDate(1).toLocalDate() + "|" + rs.getBigDecimal(2).setScale(4).toPlainString() + "|" + rs.getString(3).trim()).list().forEach(out::add);
        }
        return out;
    }

    // ------------------------------------------------------------------ commit

    public Job commit(InsightsContext ctx, UUID id, CommitRequest req) {
        ctx.requireImports(Action.UPDATE);
        if (!Boolean.TRUE.equals(req.confirm())) {
            throw Validate.invalid("confirm must be true to start the import");
        }
        String policy = req.possibleDuplicates() == null ? "" : req.possibleDuplicates().trim().toUpperCase(Locale.ROOT);
        if (!policy.equals("SKIP") && !policy.equals("IMPORT")) {
            throw Validate.invalid("possible_duplicates must be SKIP or IMPORT: say what to do with rows that may already exist");
        }
        JobRow j = job(ctx, id);
        if (List.of("COMPLETED", "PARTIAL", "FAILED").contains(j.status())) {
            if (policy.equals(j.possibleDuplicatePolicy())) {
                return view(ctx, j, true); // a retry of a finished commit: the same answer, nothing runs again
            }
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This import already finished with a different choice for possible duplicates");
        }
        if (j.status().equals("CANCELLED") || j.status().equals("NEEDS_MAPPING")) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This import is " + (j.status().equals("CANCELLED") ? "cancelled" : "not mapped yet"));
        }
        OffsetDateTime now = now(ctx);
        int claimed = jdbc.sql("""
                update finance_imports set status = 'COMMITTING', lease_until = :lease, possible_duplicate_policy = coalesce(possible_duplicate_policy, :p), cancel_requested = false, updated_at = :now
                where tenant_id = :t and id = :id and (status = 'READY' or (status = 'COMMITTING' and lease_until < :now))
                  and (possible_duplicate_policy is null or possible_duplicate_policy = :p)
                """).param("lease", now.plusSeconds(LEASE_SECONDS)).param("p", policy).param("now", now).param("t", ctx.tenantId()).param("id", id).update();
        if (claimed == 0) {
            JobRow again = job(ctx, id);
            if (again.status().equals("COMMITTING")) {
                throw new ApiException(ErrorCode.CONFLICT, "This import is already running");
            }
            if (again.possibleDuplicatePolicy() != null && !again.possibleDuplicatePolicy().equals(policy)) {
                throw new ApiException(ErrorCode.CONFLICT, "A different choice for possible duplicates was already made for this import");
            }
            return view(ctx, again, true);
        }
        Config cfg = config(j);
        boolean cancelled = false;
        while (true) {
            List<RawRow> batch = jdbc.sql("""
                    select id, row_number, raw, status, errors, normalized, possible_duplicate_of, record_id, external_id, dedupe_key from finance_import_rows
                    where tenant_id = :t and import_id = :i and status in ('VALID', 'POSSIBLE_DUPLICATE') order by row_number limit :n
                    """).param("t", ctx.tenantId()).param("i", id).param("n", BATCH).query(RawRow.class).list();
            if (batch.isEmpty()) {
                break;
            }
            if (cancelRequested(ctx, id)) {
                cancelled = true;
                break;
            }
            jdbc.sql("update finance_imports set lease_until = :lease, updated_at = :now where tenant_id = :t and id = :id").param("lease", now(ctx).plusSeconds(LEASE_SECONDS))
                    .param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
            for (RawRow row : batch) {
                commitRow(ctx, j, cfg, row, policy);
            }
        }
        return finish(ctx, id, cancelled);
    }

    private boolean cancelRequested(InsightsContext ctx, UUID id) {
        return Boolean.TRUE.equals(jdbc.sql("select cancel_requested from finance_imports where tenant_id = :t and id = :id").param("t", ctx.tenantId()).param("id", id).query(Boolean.class).single());
    }

    private void commitRow(InsightsContext ctx, JobRow j, Config cfg, RawRow row, String policy) {
        if (row.status().equals("POSSIBLE_DUPLICATE") && policy.equals("SKIP")) {
            jdbc.sql("update finance_import_rows set status = 'SKIPPED' where id = :id and status = 'POSSIBLE_DUPLICATE'").param("id", row.id()).update();
            return;
        }
        Map<String, String> v = read(row.normalized(), new TypeReference<Map<String, String>>() { });
        try {
            rowTx.executeWithoutResult(status -> {
                int claimed = jdbc.sql("update finance_import_rows set status = 'COMMITTED' where id = :id and status in ('VALID', 'POSSIBLE_DUPLICATE')").param("id", row.id()).update();
                if (claimed == 0) {
                    return; // another worker or an earlier attempt already handled this row
                }
                UUID recordId;
                String type;
                switch (j.kind()) {
                    case "STATEMENT" -> {
                        recordId = UUID.randomUUID();
                        type = "STATEMENT_LINE";
                        jdbc.sql("""
                                insert into finance_statement_lines (id, tenant_id, domain, import_id, row_number, account_id, line_date, amount, currency, description, reference, external_id, created_at, updated_at)
                                values (:id, :t, :d, :i, :n, :a, :date, :amt, :cur, :desc, :ref, :ext, :now, :now)
                                """).param("id", recordId).param("t", ctx.tenantId()).param("d", ctx.domain().name()).param("i", j.id()).param("n", row.rowNumber()).param("a", j.accountId())
                                .param("date", LocalDate.parse(v.get("date"))).param("amt", new BigDecimal(v.get("amount"))).param("cur", v.get("currency")).param("desc", v.get("description"))
                                .param("ref", v.get("reference")).param("ext", v.get("external_id")).param("now", now(ctx)).update();
                    }
                    case "TRANSACTIONS" -> {
                        type = "TRANSACTION";
                        recordId = transactions.create(ctx.personal(), new TransactionService.CreateRequest(v.get("kind"), j.accountId().toString(), v.get("amount"), v.get("currency"), v.get("date"),
                                v.get("category_id"), v.get("description"), v.get("reference"), null, null)).id();
                    }
                    default -> {
                        type = "EXPENSE";
                        recordId = expenses.create(ctx.businessContext(), new ExpenseService.Request(v.get("date"), v.get("amount"), v.get("currency"), v.get("vendor_id"), null, null, null,
                                v.get("category_id"), null, v.get("description"), v.get("reference"), null)).id();
                    }
                }
                if (row.externalId() != null) {
                    ledger(ctx, j, "EXTERNAL", row.dedupeKey(), row, type, recordId);
                } else if (row.dedupeKey() != null) {
                    ledger(ctx, j, "NATURAL", row.dedupeKey(), row, type, recordId);
                }
                jdbc.sql("update finance_import_rows set record_type = :ty, record_id = :rid where id = :id").param("ty", type).param("rid", recordId).param("id", row.id()).update();
            });
        } catch (DuplicateKeyException e) {
            // The stable id was committed by someone else between the preview and now: a duplicate, not a failure.
            jdbc.sql("update finance_import_rows set status = 'DUPLICATE', possible_duplicate_of = 'This id was already imported' where id = :id and status in ('VALID', 'POSSIBLE_DUPLICATE')")
                    .param("id", row.id()).update();
        } catch (ApiException e) {
            // The domain refused the row (for example a closed project, an archived vendor or a future date): that row is rejected, others go on.
            jdbc.sql("update finance_import_rows set status = 'REJECTED', errors = :e where id = :id and status in ('VALID', 'POSSIBLE_DUPLICATE')")
                    .param("e", write(List.of(new ErrorItem(null, e.getMessage() == null ? e.code().title() : e.getMessage())))).param("id", row.id()).update();
        }
    }

    private void ledger(InsightsContext ctx, JobRow j, String keyType, String key, RawRow row, String type, UUID recordId) {
        jdbc.sql("""
                insert into finance_import_ledger (id, tenant_id, kind, key_type, dedupe_key, import_id, row_number, record_type, record_id, created_at)
                values (:id, :t, :k, :ty, :key, :i, :n, :rt, :rid, :now)
                """).param("id", UUID.randomUUID()).param("t", ctx.tenantId()).param("k", j.kind()).param("ty", keyType).param("key", key).param("i", j.id()).param("n", row.rowNumber())
                .param("rt", type).param("rid", recordId).param("now", now(ctx)).update();
    }

    private Job finish(InsightsContext ctx, UUID id, boolean cancelled) {
        record Counts(int committed, int skipped, int rejected, int duplicate, int possible, int valid) {
        }
        Counts c = jdbc.sql("""
                select count(*) filter (where status = 'COMMITTED') as committed, count(*) filter (where status = 'SKIPPED') as skipped,
                       count(*) filter (where status in ('INVALID', 'REJECTED')) as rejected, count(*) filter (where status = 'DUPLICATE') as duplicate,
                       count(*) filter (where status = 'POSSIBLE_DUPLICATE') as possible, count(*) filter (where status = 'VALID') as valid
                from finance_import_rows where tenant_id = :t and import_id = :i
                """).param("t", ctx.tenantId()).param("i", id).query(Counts.class).single();
        String status = cancelled ? "CANCELLED" : c.rejected() > 0 ? (c.committed() > 0 ? "PARTIAL" : "FAILED") : "COMPLETED";
        jdbc.sql("""
                update finance_imports set status = :s, committed_rows = :c, skipped_rows = :k, rejected_rows = :r, duplicate_rows = :d, possible_duplicate_rows = :p, valid_rows = :v,
                       lease_until = null, cancel_requested = false, completed_at = :now, updated_at = :now where tenant_id = :t and id = :id
                """).param("s", status).param("c", c.committed()).param("k", c.skipped()).param("r", c.rejected()).param("d", c.duplicate()).param("p", c.possible()).param("v", c.valid())
                .param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "IMPORT_" + status, "IMPORT", id,
                "committed " + c.committed() + ", rejected " + c.rejected() + ", duplicates " + c.duplicate() + ", skipped " + c.skipped());
        // An in-app notice for the person who ran the import: counts only, never the file name or any row content. A repeat of the same outcome is ignored.
        notifications.create(ctx.tenantId(), ctx.clock(), ctx.userId(), "REPORTS", "IMPORT", "COMPLETED".equals(status) || "CANCELLED".equals(status) ? "INFO" : "WARNING",
                "Import " + status.toLowerCase(java.util.Locale.ROOT), c.committed() + " committed, " + c.rejected() + " rejected, " + c.duplicate() + " duplicate, " + c.skipped() + " skipped.",
                java.util.Map.of("import_id", id.toString(), "status", status), "import:" + id + ":" + status);
        return view(ctx, job(ctx, id), true);
    }

    public Job cancel(InsightsContext ctx, UUID id) {
        ctx.requireImports(Action.UPDATE);
        JobRow j = job(ctx, id);
        OffsetDateTime now = now(ctx);
        switch (j.status()) {
            case "NEEDS_MAPPING", "READY" -> {
                jdbc.sql("update finance_imports set status = 'CANCELLED', completed_at = :now, updated_at = :now where tenant_id = :t and id = :id and status in ('NEEDS_MAPPING', 'READY')")
                        .param("now", now).param("t", ctx.tenantId()).param("id", id).update();
                audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "IMPORT_CANCELLED", "IMPORT", id, null);
            }
            case "COMMITTING" -> {
                if (j.leaseUntil() != null && j.leaseUntil().isBefore(now)) {
                    // The commit was interrupted and nobody is running it: close it with the rows that were committed.
                    return finish(ctx, id, true);
                }
                jdbc.sql("update finance_imports set cancel_requested = true, updated_at = :now where tenant_id = :t and id = :id").param("now", now).param("t", ctx.tenantId()).param("id", id).update();
            }
            default -> throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This import already finished (" + j.status().toLowerCase(Locale.ROOT) + ")");
        }
        return view(ctx, job(ctx, id), true);
    }
}
