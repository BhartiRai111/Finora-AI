package com.finora.finance.commitments;

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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bills (one-off or recurring) and subscriptions (always recurring). A bill or subscription is only marked paid by an
 * explicit action that records a real expense; nothing is paid or detected automatically. Annualised and monthly costs are
 * estimates computed from the stated amount and frequency (no conversion between currencies).
 */
@Service
public class CommitmentService {

    public record Row(UUID id, String kind, String name, BigDecimal amount, String currency, String frequency, LocalDate anchorDate,
            int dueIndex, String status, UUID categoryId, UUID accountId, String note, int version) {
    }

    public record View(UUID id, String kind, String name, String amount, String currency, String frequency, LocalDate anchorDate,
            LocalDate nextDueDate, boolean overdue, String status, UUID categoryId, UUID accountId, String note, String annualizedCost,
            String monthlyEquivalent, int version) {
    }

    public record CreateRequest(String name, String amount, String currency, String frequency, String firstDueDate, String categoryId,
            String accountId, String note) {
    }

    public record UpdateRequest(String name, String amount, String categoryId, Boolean uncategorized, String accountId, String note, Integer version) {
    }

    public record PayRequest(String accountId, String paidOn, String amount) {
    }

    enum Frequency { ONCE, WEEKLY, MONTHLY, QUARTERLY, YEARLY }

    private static final String COLS = "id, kind, name, amount, currency, frequency, anchor_date, due_index, status, category_id, account_id, note, version";

    private final JdbcClient jdbc;
    private final AccountService accounts;
    private final ReferenceService reference;
    private final FinanceAudit audit;

    public CommitmentService(JdbcClient jdbc, AccountService accounts, ReferenceService reference, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.reference = reference;
        this.audit = audit;
    }

    private static OffsetDateTime now(FinanceContext ctx) {
        return OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC);
    }

    static LocalDate due(Row r, long index) {
        return switch (r.frequency()) {
            case "WEEKLY" -> r.anchorDate().plusWeeks(index);
            case "MONTHLY" -> r.anchorDate().plusMonths(index);
            case "QUARTERLY" -> r.anchorDate().plusMonths(3 * index);
            case "YEARLY" -> r.anchorDate().plusYears(index);
            default -> r.anchorDate();
        };
    }

    private static int periodsPerYear(String frequency) {
        return switch (frequency) {
            case "WEEKLY" -> 52;
            case "MONTHLY" -> 12;
            case "QUARTERLY" -> 4;
            case "YEARLY" -> 1;
            default -> 0;
        };
    }

    private View view(FinanceContext ctx, Row r) {
        boolean open = "ACTIVE".equals(r.status()) || "PAUSED".equals(r.status());
        LocalDate next = open ? due(r, r.dueIndex()) : null;
        String annual = null;
        String monthly = null;
        int periods = periodsPerYear(r.frequency());
        if (periods > 0) {
            BigDecimal a = r.amount().multiply(BigDecimal.valueOf(periods));
            annual = Currencies.format(a, r.currency());
            monthly = Currencies.format(a.divide(BigDecimal.valueOf(12), Currencies.exponentOrThrow(r.currency()), RoundingMode.HALF_EVEN), r.currency());
        }
        return new View(r.id(), r.kind(), r.name(), Currencies.format(r.amount(), r.currency()), r.currency(), r.frequency(), r.anchorDate(),
                next, next != null && "ACTIVE".equals(r.status()) && next.isBefore(ctx.today()), r.status(), r.categoryId(), r.accountId(), r.note(),
                annual, monthly, r.version());
    }

    private Row row(UUID tenant, UUID id, String kind, boolean lock) {
        return jdbc.sql("select " + COLS + " from commitments where tenant_id = :t and id = :id and kind = :k" + (lock ? " for update" : ""))
                .param("t", tenant).param("id", id).param("k", kind).query(Row.class).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    public Map<String, Object> list(FinanceContext ctx, String kind, Integer dueWithinDays) {
        ctx.require(Action.READ);
        List<View> items = jdbc.sql("select " + COLS + " from commitments where tenant_id = :t and kind = :k order by status = 'ACTIVE' desc, lower(name), id")
                .param("t", ctx.tenantId()).param("k", kind).query(Row.class).list().stream().map(r -> view(ctx, r)).toList();
        if (dueWithinDays != null) {
            LocalDate limit = ctx.today().plusDays(dueWithinDays);
            items = items.stream().filter(v -> "ACTIVE".equals(v.status()) && v.nextDueDate() != null && !v.nextDueDate().isAfter(limit))
                    .sorted(java.util.Comparator.comparing(View::nextDueDate)).toList();
        }
        // Estimated recurring cost per currency, active items only.
        Map<String, BigDecimal> yearly = new java.util.TreeMap<>();
        for (View v : items) {
            if ("ACTIVE".equals(v.status()) && v.annualizedCost() != null) {
                yearly.merge(v.currency(), new BigDecimal(v.annualizedCost()), BigDecimal::add);
            }
        }
        List<Map<String, String>> totals = yearly.entrySet().stream().map(e -> Map.of("currency", e.getKey(),
                "annualized_cost", Currencies.format(e.getValue(), e.getKey()),
                "monthly_equivalent", Currencies.format(e.getValue().divide(BigDecimal.valueOf(12), Currencies.exponentOrThrow(e.getKey()), RoundingMode.HALF_EVEN), e.getKey()))).toList();
        return Map.of("items", items, "totals", totals);
    }

    @Transactional
    public View create(FinanceContext ctx, String kind, CreateRequest req) {
        ctx.require(Action.CREATE);
        String name = Validate.text("name", req.name(), 1, 100, true);
        String currency = req.currency() == null ? null : req.currency().trim().toUpperCase(Locale.ROOT);
        if (!Currencies.supported(currency)) {
            throw Validate.invalid("currency is not supported");
        }
        BigDecimal amount = Amounts.parse("amount", req.amount(), currency, Amounts.Sign.POSITIVE);
        Frequency frequency = req.frequency() == null && "BILL".equals(kind) ? Frequency.ONCE : Validate.choice("frequency", req.frequency(), Frequency.class);
        if ("SUBSCRIPTION".equals(kind) && frequency == Frequency.ONCE) {
            throw Validate.invalid("a subscription must repeat; choose WEEKLY, MONTHLY, QUARTERLY or YEARLY");
        }
        LocalDate first = Validate.date("first_due_date", req.firstDueDate(), true);
        if (first.isAfter(ctx.today().plusYears(5))) {
            throw Validate.invalid("first_due_date is too far in the future");
        }
        UUID categoryId = req.categoryId() == null || req.categoryId().isBlank() ? null
                : reference.requireUsable(ctx.tenantId(), Validate.uuid("category_id", req.categoryId(), true), "EXPENSE", "category_id").id();
        UUID accountId = null;
        if (req.accountId() != null && !req.accountId().isBlank()) {
            AccountService.Row a = accounts.requireUsable(ctx.tenantId(), Validate.uuid("account_id", req.accountId(), true), "account_id");
            if (!a.currency().equals(currency)) {
                throw Validate.invalid("account_id must use the same currency (" + currency + ")");
            }
            accountId = a.id();
        }
        String note = Validate.text("note", req.note(), 1, 500, false);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into commitments (id, tenant_id, kind, name, amount, currency, frequency, anchor_date, category_id, account_id, note, created_at, updated_at)
                values (:id, :t, :k, :n, :a, :c, :f, :d, :cat, :acc, :note, :now, :now)
                """)
                .param("id", id).param("t", ctx.tenantId()).param("k", kind).param("n", name).param("a", amount).param("c", currency)
                .param("f", frequency.name()).param("d", first).param("cat", categoryId).param("acc", accountId).param("note", note)
                .param("now", now(ctx)).update();
        audit.record(ctx, kind + "_CREATED", "COMMITMENT", id, Currencies.format(amount, currency) + " " + currency + " " + frequency + " from " + first);
        return view(ctx, row(ctx.tenantId(), id, kind, false));
    }

    public View get(FinanceContext ctx, String kind, UUID id) {
        ctx.require(Action.READ);
        return view(ctx, row(ctx.tenantId(), id, kind, false));
    }

    @Transactional
    public View update(FinanceContext ctx, String kind, UUID id, UpdateRequest req) {
        ctx.require(Action.UPDATE);
        int version = Validate.version(req.version());
        Row r = row(ctx.tenantId(), id, kind, true);
        if (r.version() != version) {
            throw new ApiException(ErrorCode.CONFLICT, "This item was changed elsewhere; reload and try again");
        }
        String name = req.name() == null ? r.name() : Validate.text("name", req.name(), 1, 100, true);
        BigDecimal amount = req.amount() == null ? r.amount() : Amounts.parse("amount", req.amount(), r.currency(), Amounts.Sign.POSITIVE);
        UUID categoryId = r.categoryId();
        if (Boolean.TRUE.equals(req.uncategorized())) {
            categoryId = null;
        } else if (req.categoryId() != null && !req.categoryId().isBlank()) {
            categoryId = reference.requireUsable(ctx.tenantId(), Validate.uuid("category_id", req.categoryId(), true), "EXPENSE", "category_id").id();
        }
        UUID accountId = r.accountId();
        if (req.accountId() != null && !req.accountId().isBlank()) {
            AccountService.Row a = accounts.requireUsable(ctx.tenantId(), Validate.uuid("account_id", req.accountId(), true), "account_id");
            if (!a.currency().equals(r.currency())) {
                throw Validate.invalid("account_id must use the same currency (" + r.currency() + ")");
            }
            accountId = a.id();
        }
        String note = req.note() == null ? r.note() : Validate.text("note", req.note(), 1, 500, false);
        jdbc.sql("update commitments set name = :n, amount = :a, category_id = :cat, account_id = :acc, note = :note, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                .param("n", name).param("a", amount).param("cat", categoryId).param("acc", accountId).param("note", note).param("now", now(ctx))
                .param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx, kind + "_UPDATED", "COMMITMENT", id, "amount " + Currencies.format(r.amount(), r.currency()) + " -> " + Currencies.format(amount, r.currency()));
        return view(ctx, row(ctx.tenantId(), id, kind, false));
    }

    @Transactional
    public View setStatus(FinanceContext ctx, String kind, UUID id, String action) {
        ctx.require(Action.UPDATE);
        Row r = row(ctx.tenantId(), id, kind, true);
        String target = switch (action) {
            case "pause" -> "PAUSED";
            case "resume" -> "ACTIVE";
            default -> "CANCELLED";
        };
        boolean allowed = switch (action) {
            case "pause" -> "ACTIVE".equals(r.status());
            case "resume" -> "PAUSED".equals(r.status());
            default -> "ACTIVE".equals(r.status()) || "PAUSED".equals(r.status());
        };
        if (!allowed) {
            throw new ApiException(ErrorCode.CONFLICT, "That is not possible while the item is " + r.status().toLowerCase(Locale.ROOT));
        }
        jdbc.sql("update commitments set status = :s, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                .param("s", target).param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx, kind + "_" + target, "COMMITMENT", id, null);
        return view(ctx, row(ctx.tenantId(), id, kind, false));
    }

    /** Moves a recurring item on to its next due date without recording a payment (for example, paid some other way). */
    @Transactional
    public View skip(FinanceContext ctx, String kind, UUID id) {
        ctx.require(Action.UPDATE);
        Row r = row(ctx.tenantId(), id, kind, true);
        if (!"ACTIVE".equals(r.status()) || "ONCE".equals(r.frequency())) {
            throw new ApiException(ErrorCode.CONFLICT, "Only an active recurring item can be skipped");
        }
        jdbc.sql("update commitments set due_index = due_index + 1, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                .param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx, kind + "_SKIPPED", "COMMITMENT", id, "due " + due(r, r.dueIndex()));
        return view(ctx, row(ctx.tenantId(), id, kind, false));
    }

    /**
     * Records the payment as a real posted expense linked to this due date and moves the schedule on (or marks a one-off bill
     * paid). Paying the same due date twice is impossible: the row is locked and (commitment, due index) is unique.
     */
    @Transactional
    public Map<String, Object> pay(FinanceContext ctx, String kind, UUID id, PayRequest req) {
        ctx.require(Action.CREATE);
        Row r = row(ctx.tenantId(), id, kind, true);
        if (!"ACTIVE".equals(r.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "Only an active item can be paid");
        }
        UUID accountId = req.accountId() == null || req.accountId().isBlank() ? r.accountId() : Validate.uuid("account_id", req.accountId(), true);
        AccountService.Row account = accounts.requireUsable(ctx.tenantId(), accountId, "account_id");
        if (!account.currency().equals(r.currency())) {
            throw Validate.invalid("the paying account must use " + r.currency());
        }
        BigDecimal amount = req.amount() == null ? r.amount() : Amounts.parse("amount", req.amount(), r.currency(), Amounts.Sign.POSITIVE);
        LocalDate paidOn = Validate.date("paid_on", req.paidOn(), false);
        if (paidOn == null) {
            paidOn = ctx.today();
        }
        if (paidOn.isAfter(ctx.today()) || paidOn.isBefore(account.openingDate())) {
            throw Validate.invalid("paid_on must be between the account's opening date and today");
        }
        UUID txId = UUID.randomUUID();
        jdbc.sql("""
                insert into transactions (id, tenant_id, kind, status, account_id, amount, currency, category_id, occurred_on, payee, note,
                                          commitment_id, occurrence_index, created_by, created_at, updated_at)
                values (:id, :t, 'EXPENSE', 'POSTED', :a, :amt, :c, :cat, :d, :p, :n, :cm, :i, :u, :now, :now)
                """)
                .param("id", txId).param("t", ctx.tenantId()).param("a", account.id()).param("amt", amount).param("c", r.currency())
                .param("cat", r.categoryId()).param("d", paidOn).param("p", r.name()).param("n", r.note()).param("cm", id)
                .param("i", r.dueIndex()).param("u", ctx.userId()).param("now", now(ctx)).update();
        boolean once = "ONCE".equals(r.frequency());
        jdbc.sql("update commitments set due_index = due_index + 1, status = :s, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                .param("s", once ? "PAID" : "ACTIVE").param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx, kind + "_PAID", "COMMITMENT", id, Currencies.format(amount, r.currency()) + " " + r.currency() + " for due " + due(r, r.dueIndex()) + " paid on " + paidOn);
        return Map.of("transaction_id", txId, "item", view(ctx, row(ctx.tenantId(), id, kind, false)));
    }
}
