package com.finora.finance.recon;

import com.finora.commons.authz.Action;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.Validate;
import com.finora.finance.insights.InsightsContext;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciliation of imported statement lines with records that already exist (personal transactions, business payments received and
 * business expenses) and, through them, their supporting documents. Works entirely from manually imported files: there is no bank feed.
 *
 * <p>Matching is transparent: a record is a candidate only when currency, amount and direction (money in or out) are exactly equal; the date,
 * reference and name then raise a visible score (50 + date 30/20/10 + reference 20 + name 10, at most 100). Suggestions are never applied by
 * themselves: a match exists only after a user confirms one specific record, ambiguity is shown as such, one line matches at most one record and
 * one record at most one line (database-enforced while the match stands), and a confirmed match can be undone with a reason (kept as history).
 */
@Service
public class ReconciliationService {

    private static final int WINDOW_DAYS = 7;
    private static final int MAX_CANDIDATES = 5;

    public record Candidate(String targetType, UUID targetId, LocalDate date, String amount, String currency, String label, String reference, boolean hasDocument, int score,
            List<String> signals) {
    }

    public record MatchView(UUID id, String status, String targetType, UUID targetId, String targetLabel, boolean targetStillValid, int score, List<String> signals,
            OffsetDateTime confirmedAt, OffsetDateTime undoneAt, String undoReason) {
    }

    public record Line(UUID id, UUID importId, LocalDate date, String amount, String currency, String direction, String description, String reference, UUID accountId, String status,
            String ignoreReason, String suggestion, List<Candidate> candidates, MatchView match, int version) {
    }

    public record Page(List<Line> items, long total, int limit, int offset) {
    }

    public record UnmatchedRecord(String targetType, UUID targetId, LocalDate date, String amount, String currency, String label, String reference) {
    }

    public record Summary(long unmatched, long matched, long ignored, long unmatchedRecords) {
    }

    public record MatchRequest(String targetType, String targetId, Boolean confirm) {
    }

    public record ReasonRequest(String reason) {
    }

    private record LineRow(UUID id, UUID importId, LocalDate lineDate, BigDecimal amount, String currency, String description, String reference, UUID accountId, String status,
            String ignoreReason, int version) {
    }

    private record Target(String type, UUID id, LocalDate date, BigDecimal amount, String currency, String label, String reference, boolean hasDocument) {
    }

    private record MatchRow(UUID id, UUID lineId, String targetType, UUID targetId, String status, int score, String signals, OffsetDateTime confirmedAt, OffsetDateTime undoneAt,
            String undoReason) {
    }

    private static final String LINE_COLS = "id, import_id, line_date, amount, currency, description, reference, account_id, status, ignore_reason, version";

    private final JdbcClient jdbc;
    private final FinanceAudit audit;

    public ReconciliationService(JdbcClient jdbc, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static OffsetDateTime now(InsightsContext ctx) {
        return OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ candidate search

    private List<Target> targets(InsightsContext ctx, LineRow l, LocalDate from, LocalDate to) {
        boolean in = l.amount().signum() > 0;
        BigDecimal amount = l.amount().abs();
        String cur = l.currency().trim();
        if (!ctx.business()) {
            return jdbc.sql("""
                    select t.id, t.occurred_on as date, t.amount, t.currency, coalesce(t.payee, t.note, t.kind) as label, t.note as reference,
                           exists (select 1 from finance_documents d where d.tenant_id = t.tenant_id and d.linked_type = 'TRANSACTION' and d.linked_id = t.id and d.deleted_at is null) as has_document
                    from transactions t
                    where t.tenant_id = :t and t.account_id = :a and t.status = 'POSTED' and t.currency = :c and t.amount = :amt and t.kind in (%s) and t.occurred_on between :f and :to
                      and not exists (select 1 from finance_reconciliation_matches m where m.tenant_id = t.tenant_id and m.target_type = 'TRANSACTION' and m.target_id = t.id and m.status = 'CONFIRMED')
                    order by t.occurred_on, t.id limit 200
                    """.formatted(in ? "'INCOME', 'REFUND'" : "'EXPENSE'")).param("t", ctx.tenantId()).param("a", l.accountId()).param("c", cur).param("amt", amount).param("f", from)
                    .param("to", to).query((rs, i) -> new Target("TRANSACTION", rs.getObject(1, UUID.class), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3), rs.getString(4).trim(),
                            rs.getString(5), rs.getString(6), rs.getBoolean(7))).list();
        }
        if (in) {
            return jdbc.sql("""
                    select p.id, p.payment_date as date, p.amount, p.currency, c.name as label, p.reference, false as has_document
                    from payments p join clients c on c.tenant_id = p.tenant_id and c.id = p.client_id
                    where p.tenant_id = :t and p.status = 'RECORDED' and p.currency = :c and p.amount = :amt and p.payment_date between :f and :to
                      and not exists (select 1 from finance_reconciliation_matches m where m.tenant_id = p.tenant_id and m.target_type = 'PAYMENT' and m.target_id = p.id and m.status = 'CONFIRMED')
                    order by p.payment_date, p.id limit 200
                    """).param("t", ctx.tenantId()).param("c", cur).param("amt", amount).param("f", from).param("to", to)
                    .query((rs, i) -> new Target("PAYMENT", rs.getObject(1, UUID.class), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3), rs.getString(4).trim(), rs.getString(5),
                            rs.getString(6), rs.getBoolean(7))).list();
        }
        return jdbc.sql("""
                select e.id, e.expense_date as date, e.amount, e.currency, coalesce(v.name, e.description) as label, e.reference,
                       exists (select 1 from finance_documents d where d.tenant_id = e.tenant_id and d.linked_type = 'EXPENSE' and d.linked_id = e.id and d.deleted_at is null) as has_document
                from business_expenses e left join vendors v on v.tenant_id = e.tenant_id and v.id = e.vendor_id
                where e.tenant_id = :t and e.status = 'POSTED' and e.currency = :c and e.amount = :amt and e.expense_date between :f and :to
                  and not exists (select 1 from finance_reconciliation_matches m where m.tenant_id = e.tenant_id and m.target_type = 'EXPENSE' and m.target_id = e.id and m.status = 'CONFIRMED')
                order by e.expense_date, e.id limit 200
                """).param("t", ctx.tenantId()).param("c", cur).param("amt", amount).param("f", from).param("to", to)
                .query((rs, i) -> new Target("EXPENSE", rs.getObject(1, UUID.class), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3), rs.getString(4).trim(), rs.getString(5),
                        rs.getString(6), rs.getBoolean(7))).list();
    }

    static Candidate score(LineRow l, Target t) {
        List<String> signals = new ArrayList<>(List.of("same amount and currency", l.amount().signum() > 0 ? "money in" : "money out"));
        int score = 50;
        long days = Math.abs(ChronoUnit.DAYS.between(l.lineDate(), t.date()));
        if (days == 0) {
            score += 30;
            signals.add("same date");
        } else if (days <= 1) {
            score += 20;
            signals.add("1 day apart");
        } else if (days <= 3) {
            score += 10;
            signals.add(days + " days apart");
        } else {
            signals.add(days + " days apart");
        }
        if (l.reference() != null && t.reference() != null && normalize(l.reference()).equals(normalize(t.reference())) && !normalize(l.reference()).isEmpty()) {
            score += 20;
            signals.add("same reference");
        }
        if (l.description() != null && t.label() != null && t.label().length() >= 3 && l.description().toLowerCase(Locale.ROOT).contains(t.label().toLowerCase(Locale.ROOT))) {
            score += 10;
            signals.add("name appears in the description");
        }
        return new Candidate(t.type(), t.id(), t.date(), Currencies.format(t.amount(), t.currency()), t.currency(), t.label(), t.reference(), t.hasDocument(), Math.min(100, score), signals);
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private List<Candidate> candidates(InsightsContext ctx, LineRow l) {
        List<Candidate> all = new ArrayList<>();
        for (Target t : targets(ctx, l, l.lineDate().minusDays(WINDOW_DAYS), l.lineDate().plusDays(WINDOW_DAYS))) {
            all.add(score(l, t));
        }
        all.sort(Comparator.comparingInt(Candidate::score).reversed().thenComparing(Candidate::date).thenComparing(Candidate::targetId));
        return all;
    }

    /** NONE, WEAK, STRONG or AMBIGUOUS: a hint for the reviewer only. Nothing is applied automatically. */
    static String suggestion(List<Candidate> c) {
        if (c.isEmpty()) {
            return "NONE";
        }
        if (c.size() > 1 && c.get(0).score() - c.get(1).score() < 20) {
            return "AMBIGUOUS";
        }
        return c.get(0).score() >= 80 ? "STRONG" : "WEAK";
    }

    // ------------------------------------------------------------------ queries

    private LineRow line(InsightsContext ctx, UUID id, boolean lock) {
        return jdbc.sql("select " + LINE_COLS + " from finance_statement_lines where tenant_id = :t and id = :id" + (lock ? " for update" : "")).param("t", ctx.tenantId()).param("id", id)
                .query(LineRow.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    private MatchView match(InsightsContext ctx, MatchRow m) {
        String label = null;
        boolean valid = false;
        switch (m.targetType()) {
            case "TRANSACTION" -> {
                var r = jdbc.sql("select coalesce(payee, note, kind), status from transactions where tenant_id = :t and id = :id").param("t", ctx.tenantId()).param("id", m.targetId())
                        .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)}).optional();
                label = r.map(a -> a[0]).orElse(null);
                valid = r.map(a -> a[1].equals("POSTED")).orElse(false);
            }
            case "PAYMENT" -> {
                var r = jdbc.sql("select c.name, p.status from payments p join clients c on c.tenant_id = p.tenant_id and c.id = p.client_id where p.tenant_id = :t and p.id = :id")
                        .param("t", ctx.tenantId()).param("id", m.targetId()).query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)}).optional();
                label = r.map(a -> a[0]).orElse(null);
                valid = r.map(a -> a[1].equals("RECORDED")).orElse(false);
            }
            default -> {
                var r = jdbc.sql("select coalesce(v.name, e.description), e.status from business_expenses e left join vendors v on v.tenant_id = e.tenant_id and v.id = e.vendor_id where e.tenant_id = :t and e.id = :id")
                        .param("t", ctx.tenantId()).param("id", m.targetId()).query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)}).optional();
                label = r.map(a -> a[0]).orElse(null);
                valid = r.map(a -> a[1].equals("POSTED")).orElse(false);
            }
        }
        return new MatchView(m.id(), m.status(), m.targetType(), m.targetId(), label, valid, m.score(), m.signals() == null ? List.of() : List.of(m.signals().split("; ")), m.confirmedAt(), m.undoneAt(), m.undoReason());
    }

    private Line view(InsightsContext ctx, LineRow l, boolean withCandidates) {
        List<Candidate> candidates = null;
        String suggestion = null;
        MatchView match = null;
        if (l.status().equals("UNMATCHED") && withCandidates) {
            List<Candidate> all = candidates(ctx, l);
            suggestion = suggestion(all);
            candidates = all.size() > MAX_CANDIDATES ? all.subList(0, MAX_CANDIDATES) : all;
        }
        if (l.status().equals("MATCHED")) {
            match = jdbc.sql("select id, line_id, target_type, target_id, status, score, signals, confirmed_at, undone_at, undo_reason from finance_reconciliation_matches where tenant_id = :t and line_id = :l and status = 'CONFIRMED'")
                    .param("t", ctx.tenantId()).param("l", l.id()).query(MatchRow.class).optional().map(m -> match(ctx, m)).orElse(null);
        }
        String cur = l.currency().trim();
        return new Line(l.id(), l.importId(), l.lineDate(), Currencies.format(l.amount(), cur), cur, l.amount().signum() > 0 ? "IN" : "OUT", l.description(), l.reference(), l.accountId(),
                l.status(), l.ignoreReason(), suggestion, candidates, match, l.version());
    }

    public Page lines(InsightsContext ctx, String status, UUID accountId, Integer limit, Integer offset) {
        ctx.requireImports(Action.READ);
        int lim = limit == null ? 20 : limit;
        int off = offset == null ? 0 : offset;
        if (lim < 1 || lim > 50 || off < 0) {
            throw Validate.invalid("limit must be 1-50 and offset 0 or more");
        }
        String st = status == null || status.isBlank() ? "UNMATCHED" : status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("UNMATCHED", "MATCHED", "IGNORED").contains(st)) {
            throw Validate.invalid("status must be UNMATCHED, MATCHED or IGNORED");
        }
        String where = "tenant_id = :t and status = :s" + (accountId == null ? "" : " and account_id = :a");
        var count = jdbc.sql("select count(*) from finance_statement_lines where " + where).param("t", ctx.tenantId()).param("s", st);
        var query = jdbc.sql("select " + LINE_COLS + " from finance_statement_lines where " + where + " order by line_date desc, id limit :l offset :o").param("t", ctx.tenantId()).param("s", st)
                .param("l", lim).param("o", off);
        if (accountId != null) {
            count = count.param("a", accountId);
            query = query.param("a", accountId);
        }
        return new Page(query.query(LineRow.class).list().stream().map(l -> view(ctx, l, true)).toList(), count.query(Long.class).single(), lim, off);
    }

    public Line get(InsightsContext ctx, UUID id) {
        ctx.requireImports(Action.READ);
        return view(ctx, line(ctx, id, false), true);
    }

    public Summary summary(InsightsContext ctx) {
        ctx.requireImports(Action.READ);
        record C(long u, long m, long i) {
        }
        C c = jdbc.sql("select count(*) filter (where status = 'UNMATCHED') as u, count(*) filter (where status = 'MATCHED') as m, count(*) filter (where status = 'IGNORED') as i from finance_statement_lines where tenant_id = :t")
                .param("t", ctx.tenantId()).query(C.class).single();
        return new Summary(c.u(), c.m(), c.i(), unmatchedRecords(ctx, null, 1, 0).size() > 0 ? countUnmatchedRecords(ctx) : 0);
    }

    private long countUnmatchedRecords(InsightsContext ctx) {
        return unmatchedRecords(ctx, null, 10_000, 0).size();
    }

    /** Records inside the period covered by imported statement lines that no confirmed match points to. */
    public List<UnmatchedRecord> unmatchedRecords(InsightsContext ctx, UUID accountId, int limit, int offset) {
        ctx.requireImports(Action.READ);
        if (limit < 1 || limit > 10_000 || offset < 0) {
            throw Validate.invalid("limit must be 1-10000 and offset 0 or more");
        }
        List<UnmatchedRecord> out = new ArrayList<>();
        if (!ctx.business()) {
            var cover = jdbc.sql("select min(line_date), max(line_date) from finance_statement_lines where tenant_id = :t" + (accountId == null ? "" : " and account_id = :a"))
                    .param("t", ctx.tenantId());
            if (accountId != null) {
                cover = cover.param("a", accountId);
            }
            LocalDate[] range = cover.query((rs, i) -> new LocalDate[] {rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate(), rs.getDate(2) == null ? null : rs.getDate(2).toLocalDate()}).single();
            if (range[0] == null) {
                return out;
            }
            var q = jdbc.sql("""
                    select t.id, t.occurred_on, t.amount, t.currency, coalesce(t.payee, t.note, t.kind), t.note, t.kind
                    from transactions t
                    where t.tenant_id = :t and t.status = 'POSTED' and t.kind in ('INCOME', 'EXPENSE', 'REFUND') and t.occurred_on between :f and :to
                      and t.account_id in (select distinct account_id from finance_statement_lines where tenant_id = :t and account_id is not null%s)
                      and not exists (select 1 from finance_reconciliation_matches m where m.tenant_id = t.tenant_id and m.target_type = 'TRANSACTION' and m.target_id = t.id and m.status = 'CONFIRMED')
                    order by t.occurred_on desc, t.id limit :l offset :o
                    """.formatted(accountId == null ? "" : " and account_id = :a")).param("t", ctx.tenantId()).param("f", range[0]).param("to", range[1]).param("l", limit).param("o", offset);
            if (accountId != null) {
                q = q.param("a", accountId);
            }
            q.query((rs, i) -> new UnmatchedRecord("TRANSACTION", rs.getObject(1, UUID.class), rs.getDate(2).toLocalDate(), Currencies.format(rs.getBigDecimal(3), rs.getString(4).trim()),
                    rs.getString(4).trim(), rs.getString(5), rs.getString(6))).list().forEach(out::add);
            return out;
        }
        LocalDate[] range = jdbc.sql("select min(line_date), max(line_date) from finance_statement_lines where tenant_id = :t").param("t", ctx.tenantId())
                .query((rs, i) -> new LocalDate[] {rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate(), rs.getDate(2) == null ? null : rs.getDate(2).toLocalDate()}).single();
        if (range[0] == null) {
            return out;
        }
        jdbc.sql("""
                select * from (
                  select 'PAYMENT' as type, p.id, p.payment_date as d, p.amount, p.currency, c.name as label, p.reference as ref
                  from payments p join clients c on c.tenant_id = p.tenant_id and c.id = p.client_id
                  where p.tenant_id = :t and p.status = 'RECORDED' and p.payment_date between :f and :to
                    and not exists (select 1 from finance_reconciliation_matches m where m.tenant_id = p.tenant_id and m.target_type = 'PAYMENT' and m.target_id = p.id and m.status = 'CONFIRMED')
                  union all
                  select 'EXPENSE', e.id, e.expense_date, e.amount, e.currency, coalesce(v.name, e.description), e.reference
                  from business_expenses e left join vendors v on v.tenant_id = e.tenant_id and v.id = e.vendor_id
                  where e.tenant_id = :t and e.status = 'POSTED' and e.expense_date between :f and :to
                    and not exists (select 1 from finance_reconciliation_matches m where m.tenant_id = e.tenant_id and m.target_type = 'EXPENSE' and m.target_id = e.id and m.status = 'CONFIRMED')
                ) x order by d desc, id limit :l offset :o
                """).param("t", ctx.tenantId()).param("f", range[0]).param("to", range[1]).param("l", limit).param("o", offset)
                .query((rs, i) -> new UnmatchedRecord(rs.getString(1), rs.getObject(2, UUID.class), rs.getDate(3).toLocalDate(), Currencies.format(rs.getBigDecimal(4), rs.getString(5).trim()),
                        rs.getString(5).trim(), rs.getString(6), rs.getString(7))).list().forEach(out::add);
        return out;
    }

    // ------------------------------------------------------------------ actions

    /** Confirms ONE specific record for a line. Needs confirm=true; nothing is ever matched automatically. */
    @Transactional
    public Line confirm(InsightsContext ctx, UUID lineId, MatchRequest req) {
        ctx.requireImports(Action.UPDATE);
        if (!Boolean.TRUE.equals(req.confirm())) {
            throw Validate.invalid("confirm must be true: reconciliation is only recorded when you confirm a specific match");
        }
        String type = req.targetType() == null ? "" : req.targetType().trim().toUpperCase(Locale.ROOT);
        UUID targetId = Validate.uuid("target_id", req.targetId(), true);
        List<String> allowed = ctx.business() ? List.of("PAYMENT", "EXPENSE") : List.of("TRANSACTION");
        if (!allowed.contains(type)) {
            throw Validate.invalid("target_type must be one of " + allowed);
        }
        LineRow l = line(ctx, lineId, true);
        if (l.status().equals("MATCHED")) {
            MatchRow existing = jdbc.sql("select id, line_id, target_type, target_id, status, score, signals, confirmed_at, undone_at, undo_reason from finance_reconciliation_matches where tenant_id = :t and line_id = :l and status = 'CONFIRMED'")
                    .param("t", ctx.tenantId()).param("l", lineId).query(MatchRow.class).single();
            if (existing.targetType().equals(type) && existing.targetId().equals(targetId)) {
                return view(ctx, l, false); // the same confirmation again: nothing changes
            }
            throw new ApiException(ErrorCode.CONFLICT, "This statement line is already reconciled; undo that match first");
        }
        if (!l.status().equals("UNMATCHED")) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This statement line is ignored; restore it first");
        }
        // A user may confirm a match whose date is further away than the suggestion window, but amount, currency and direction must be exact.
        Target t = targets(ctx, l, LocalDate.of(2000, 1, 1), LocalDate.of(2100, 12, 31)).stream().filter(x -> x.type().equals(type) && x.id().equals(targetId)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "That record cannot be matched to this line: it must exist, still count, be unreconciled and have the same amount, currency and direction"));
        Candidate c = score(l, t);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into finance_reconciliation_matches (id, tenant_id, line_id, target_type, target_id, score, signals, confirmed_by, confirmed_at)
                    values (:id, :t, :l, :ty, :tid, :s, :sig, :u, :now)
                    """).param("id", id).param("t", ctx.tenantId()).param("l", lineId).param("ty", type).param("tid", targetId).param("s", c.score()).param("sig", String.join("; ", c.signals()))
                    .param("u", ctx.userId()).param("now", now(ctx)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "That record is already reconciled with another statement line");
        }
        jdbc.sql("update finance_statement_lines set status = 'MATCHED', version = version + 1, updated_at = :now where tenant_id = :t and id = :id").param("now", now(ctx)).param("t", ctx.tenantId()).param("id", lineId).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "RECONCILIATION_CONFIRMED", "STATEMENT_LINE", lineId, type + " " + targetId + " score " + c.score());
        return view(ctx, line(ctx, lineId, false), false);
    }

    @Transactional
    public Line undo(InsightsContext ctx, UUID matchId, ReasonRequest req) {
        ctx.requireImports(Action.UPDATE);
        String reason = Validate.text("reason", req.reason(), 1, 200, true);
        MatchRow m = jdbc.sql("select id, line_id, target_type, target_id, status, score, signals, confirmed_at, undone_at, undo_reason from finance_reconciliation_matches where tenant_id = :t and id = :id for update")
                .param("t", ctx.tenantId()).param("id", matchId).query(MatchRow.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
        if (!m.status().equals("CONFIRMED")) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This match was already undone");
        }
        LineRow l = line(ctx, m.lineId(), true);
        jdbc.sql("update finance_reconciliation_matches set status = 'UNDONE', undone_by = :u, undone_at = :now, undo_reason = :r where tenant_id = :t and id = :id").param("u", ctx.userId()).param("now", now(ctx))
                .param("r", reason).param("t", ctx.tenantId()).param("id", matchId).update();
        jdbc.sql("update finance_statement_lines set status = 'UNMATCHED', version = version + 1, updated_at = :now where tenant_id = :t and id = :id").param("now", now(ctx)).param("t", ctx.tenantId()).param("id", l.id()).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "RECONCILIATION_UNDONE", "STATEMENT_LINE", l.id(), m.targetType() + " " + m.targetId());
        return view(ctx, line(ctx, l.id(), false), true);
    }

    @Transactional
    public Line ignore(InsightsContext ctx, UUID lineId, ReasonRequest req, boolean ignore) {
        ctx.requireImports(Action.UPDATE);
        LineRow l = line(ctx, lineId, true);
        if (ignore) {
            String reason = Validate.text("reason", req.reason(), 1, 200, true);
            if (!l.status().equals("UNMATCHED")) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Only an unmatched line can be ignored");
            }
            jdbc.sql("update finance_statement_lines set status = 'IGNORED', ignore_reason = :r, version = version + 1, updated_at = :now where tenant_id = :t and id = :id").param("r", reason).param("now", now(ctx))
                    .param("t", ctx.tenantId()).param("id", lineId).update();
        } else {
            if (!l.status().equals("IGNORED")) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This line is not ignored");
            }
            jdbc.sql("update finance_statement_lines set status = 'UNMATCHED', ignore_reason = null, version = version + 1, updated_at = :now where tenant_id = :t and id = :id").param("now", now(ctx))
                    .param("t", ctx.tenantId()).param("id", lineId).update();
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), ignore ? "STATEMENT_LINE_IGNORED" : "STATEMENT_LINE_RESTORED", "STATEMENT_LINE", lineId, null);
        return view(ctx, line(ctx, lineId, false), true);
    }
}
