package com.finora.finance.recurring;

import com.finora.commons.authz.Action;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.accounts.AccountService;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.FinanceContext;
import com.finora.finance.core.Validate;
import com.finora.finance.money.Amounts;
import com.finora.finance.money.Currencies;
import com.finora.finance.reference.ReferenceService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recurring income and expenses (TX-6). Occurrence {@code k} is computed from the start date (never from the previous
 * occurrence), so a rule that starts on the 31st lands on the last day of shorter months and returns to the 31st
 * afterwards. Generation is idempotent per occurrence (a unique index) and catches up missed occurrences exactly once.
 * Generated transactions are DRAFTs unless the rule opted in to auto-post (DEC-37 default).
 */
@Service
public class RecurringService {

    static final int CATCH_UP_LIMIT = 366;

    public record Rule(UUID id, String kind, UUID accountId, UUID categoryId, BigDecimal amount, String currency, String payee,
            String note, String frequency, int intervalCount, LocalDate startDate, LocalDate endDate, int nextIndex,
            boolean autoPost, boolean active, int version) {
    }

    public record View(UUID id, String kind, UUID accountId, UUID categoryId, String amount, String currency, String payee, String note,
            String frequency, int intervalCount, LocalDate startDate, LocalDate endDate, LocalDate nextOccurrence, boolean autoPost,
            boolean active, int version) {
    }

    public record CreateRequest(String kind, String accountId, String categoryId, String amount, String payee, String note,
            String frequency, Integer intervalCount, String startDate, String endDate, Boolean autoPost) {
    }

    public record UpdateRequest(String amount, String categoryId, Boolean uncategorized, String payee, String note, String endDate,
            Boolean clearEndDate, Boolean autoPost, Integer version) {
    }

    enum Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

    enum RuleKind { INCOME, EXPENSE }

    private static final String COLS = "id, kind, account_id, category_id, amount, currency, payee, note, frequency, interval_count, "
            + "start_date, end_date, next_index, auto_post, active, version";

    private final JdbcClient jdbc;
    private final AccountService accounts;
    private final ReferenceService reference;
    private final FinanceAudit audit;

    public RecurringService(JdbcClient jdbc, AccountService accounts, ReferenceService reference, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.reference = reference;
        this.audit = audit;
    }

    private static OffsetDateTime now(FinanceContext ctx) {
        return OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC);
    }

    /** The date of occurrence {@code index}, anchored to the start date. */
    public static LocalDate occurrence(Rule r, long index) {
        long steps = index * r.intervalCount();
        return switch (r.frequency()) {
            case "DAILY" -> r.startDate().plusDays(steps);
            case "WEEKLY" -> r.startDate().plusWeeks(steps);
            case "MONTHLY" -> r.startDate().plusMonths(steps);
            default -> r.startDate().plusYears(steps);
        };
    }

    static LocalDate nextOccurrence(Rule r) {
        LocalDate next = occurrence(r, r.nextIndex());
        return r.endDate() != null && next.isAfter(r.endDate()) ? null : next;
    }

    private View view(Rule r) {
        return new View(r.id(), r.kind(), r.accountId(), r.categoryId(), Currencies.format(r.amount(), r.currency()), r.currency(),
                r.payee(), r.note(), r.frequency(), r.intervalCount(), r.startDate(), r.endDate(), nextOccurrence(r), r.autoPost(),
                r.active(), r.version());
    }

    private Rule rule(UUID tenant, UUID id, boolean lock) {
        return jdbc.sql("select " + COLS + " from recurring_rules where tenant_id = :t and id = :id" + (lock ? " for update" : ""))
                .param("t", tenant).param("id", id).query(Rule.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    public List<View> list(FinanceContext ctx) {
        ctx.require(Action.READ);
        return jdbc.sql("select " + COLS + " from recurring_rules where tenant_id = :t order by active desc, start_date, id")
                .param("t", ctx.tenantId()).query(Rule.class).list().stream().map(this::view).toList();
    }

    @Transactional
    public View create(FinanceContext ctx, CreateRequest req) {
        ctx.require(Action.CREATE);
        RuleKind kind = Validate.choice("kind", req.kind(), RuleKind.class);
        Frequency frequency = Validate.choice("frequency", req.frequency(), Frequency.class);
        AccountService.Row account = accounts.requireUsable(ctx.tenantId(), Validate.uuid("account_id", req.accountId(), true), "account_id");
        BigDecimal amount = Amounts.parse("amount", req.amount(), account.currency(), Amounts.Sign.POSITIVE);
        int interval = req.intervalCount() == null ? 1 : req.intervalCount();
        if (interval < 1 || interval > 366) {
            throw Validate.invalid("interval_count must be between 1 and 366");
        }
        LocalDate start = Validate.date("start_date", req.startDate(), true);
        LocalDate end = Validate.date("end_date", req.endDate(), false);
        if (end != null && end.isBefore(start)) {
            throw Validate.invalid("end_date must not be before start_date");
        }
        if (start.isAfter(ctx.today().plusYears(2))) {
            throw Validate.invalid("start_date is too far in the future");
        }
        UUID categoryId = req.categoryId() == null || req.categoryId().isBlank() ? null
                : reference.requireUsable(ctx.tenantId(), Validate.uuid("category_id", req.categoryId(), true), kind.name(), "category_id").id();
        String payee = Validate.text("payee", req.payee(), 1, 120, false);
        String note = Validate.text("note", req.note(), 1, 1000, false);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into recurring_rules (id, tenant_id, kind, account_id, category_id, amount, currency, payee, note, frequency,
                                             interval_count, start_date, end_date, auto_post, created_at, updated_at)
                values (:id, :t, :k, :a, :cat, :amt, :c, :p, :n, :f, :i, :s, :e, :ap, :now, :now)
                """)
                .param("id", id).param("t", ctx.tenantId()).param("k", kind.name()).param("a", account.id()).param("cat", categoryId)
                .param("amt", amount).param("c", account.currency()).param("p", payee).param("n", note).param("f", frequency.name())
                .param("i", interval).param("s", start).param("e", end).param("ap", Boolean.TRUE.equals(req.autoPost()))
                .param("now", now(ctx)).update();
        audit.record(ctx, "RECURRING_CREATED", "RECURRING_RULE", id, kind + " " + Currencies.format(amount, account.currency()) + " "
                + frequency + " x" + interval + " from " + start);
        return view(rule(ctx.tenantId(), id, false));
    }

    /** Only future occurrences are affected; the schedule itself (start, frequency, account) is fixed once created. */
    @Transactional
    public View update(FinanceContext ctx, UUID id, UpdateRequest req) {
        ctx.require(Action.UPDATE);
        int version = Validate.version(req.version());
        Rule r = rule(ctx.tenantId(), id, true);
        if (r.version() != version) {
            throw new ApiException(ErrorCode.CONFLICT, "This rule was changed elsewhere; reload and try again");
        }
        BigDecimal amount = req.amount() == null ? r.amount() : Amounts.parse("amount", req.amount(), r.currency(), Amounts.Sign.POSITIVE);
        UUID categoryId = r.categoryId();
        if (Boolean.TRUE.equals(req.uncategorized())) {
            categoryId = null;
        } else if (req.categoryId() != null && !req.categoryId().isBlank()) {
            categoryId = reference.requireUsable(ctx.tenantId(), Validate.uuid("category_id", req.categoryId(), true), r.kind(), "category_id").id();
        }
        LocalDate end = Boolean.TRUE.equals(req.clearEndDate()) ? null : (req.endDate() == null ? r.endDate() : Validate.date("end_date", req.endDate(), true));
        if (end != null && end.isBefore(r.startDate())) {
            throw Validate.invalid("end_date must not be before start_date");
        }
        String payee = req.payee() == null ? r.payee() : Validate.text("payee", req.payee(), 1, 120, false);
        String note = req.note() == null ? r.note() : Validate.text("note", req.note(), 1, 1000, false);
        boolean autoPost = req.autoPost() == null ? r.autoPost() : req.autoPost();
        jdbc.sql("""
                update recurring_rules set amount = :amt, category_id = :cat, payee = :p, note = :n, end_date = :e, auto_post = :ap,
                       version = version + 1, updated_at = :now where tenant_id = :t and id = :id
                """)
                .param("amt", amount).param("cat", categoryId).param("p", payee).param("n", note).param("e", end).param("ap", autoPost)
                .param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx, "RECURRING_UPDATED", "RECURRING_RULE", id, "amount " + Currencies.format(r.amount(), r.currency()) + " -> "
                + Currencies.format(amount, r.currency()) + ", auto_post " + r.autoPost() + " -> " + autoPost);
        return view(rule(ctx.tenantId(), id, false));
    }

    @Transactional
    public View pause(FinanceContext ctx, UUID id) {
        ctx.require(Action.UPDATE);
        Rule r = rule(ctx.tenantId(), id, true);
        if (!r.active()) {
            throw new ApiException(ErrorCode.CONFLICT, "Already paused");
        }
        jdbc.sql("update recurring_rules set active = false, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                .param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx, "RECURRING_PAUSED", "RECURRING_RULE", id, null);
        return view(rule(ctx.tenantId(), id, false));
    }

    /** Resuming skips occurrences that fell due while paused: nothing is back-filled for the paused period. */
    @Transactional
    public View resume(FinanceContext ctx, UUID id) {
        ctx.require(Action.UPDATE);
        Rule r = rule(ctx.tenantId(), id, true);
        if (r.active()) {
            throw new ApiException(ErrorCode.CONFLICT, "Already active");
        }
        int index = r.nextIndex();
        LocalDate today = ctx.today();
        while (occurrence(r, index).isBefore(today) && (r.endDate() == null || !occurrence(r, index).isAfter(r.endDate()))) {
            index++;
        }
        jdbc.sql("update recurring_rules set active = true, next_index = :i, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                .param("i", index).param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx, "RECURRING_RESUMED", "RECURRING_RULE", id, (index - r.nextIndex()) + " occurrences skipped");
        return view(rule(ctx.tenantId(), id, false));
    }

    /** Generates every due occurrence for this user's rules, as of their local date. Returns how many were created. */
    @Transactional
    public int generateDue(FinanceContext ctx) {
        ctx.require(Action.CREATE);
        return generate(ctx);
    }

    /** Used by the scheduler (system actor); same logic, no user permission check. */
    @Transactional
    public int generateDueAsSystem(FinanceContext systemCtx) {
        return generate(systemCtx);
    }

    private int generate(FinanceContext ctx) {
        LocalDate today = ctx.today();
        List<Rule> rules = jdbc.sql("select " + COLS + " from recurring_rules where tenant_id = :t and active order by id for update")
                .param("t", ctx.tenantId()).query(Rule.class).list();
        int total = 0;
        for (Rule r : rules) {
            AccountService.Row account = accounts.require(ctx.tenantId(), r.accountId());
            if (account.archivedAt() != null) {
                continue; // nothing is posted to a closed account; the rule keeps its place until it is reopened or paused
            }
            int index = r.nextIndex();
            int created = 0;
            int steps = 0;
            while (steps < CATCH_UP_LIMIT) {
                LocalDate date = occurrence(r, index);
                if (date.isAfter(today) || (r.endDate() != null && date.isAfter(r.endDate()))) {
                    break;
                }
                if (!date.isBefore(account.openingDate())) {
                    created += jdbc.sql("""
                            insert into transactions (id, tenant_id, kind, status, account_id, amount, currency, category_id, occurred_on, payee, note,
                                                      recurring_rule_id, occurrence_index, created_by, created_at, updated_at)
                            values (:id, :t, :k, :st, :a, :amt, :c, :cat, :d, :p, :n, :r, :i, :u, :now, :now)
                            on conflict do nothing
                            """)
                            .param("id", UUID.randomUUID()).param("t", ctx.tenantId()).param("k", r.kind()).param("st", r.autoPost() ? "POSTED" : "DRAFT")
                            .param("a", r.accountId()).param("amt", r.amount()).param("c", r.currency()).param("cat", r.categoryId())
                            .param("d", date).param("p", r.payee()).param("n", r.note()).param("r", r.id()).param("i", index)
                            .param("u", ctx.userId()).param("now", now(ctx)).update();
                }
                index++;
                steps++;
            }
            if (index != r.nextIndex()) {
                jdbc.sql("update recurring_rules set next_index = :i, updated_at = :now where tenant_id = :t and id = :id")
                        .param("i", index).param("now", now(ctx)).param("t", ctx.tenantId()).param("id", r.id()).update();
            }
            if (created > 0) {
                audit.record(ctx, "RECURRING_GENERATED", "RECURRING_RULE", r.id(), created + " " + (r.autoPost() ? "posted" : "draft") + " transaction(s)");
            }
            total += created;
        }
        return total;
    }
}
