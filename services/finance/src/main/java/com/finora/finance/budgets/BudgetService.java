package com.finora.finance.budgets;

import com.finora.commons.authz.Action;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
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
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly budgets (overall or per expense category) and budget-versus-actual. Spent = posted expenses minus posted refunds
 * in the category (and its subcategories) and currency, for the calendar month in the user's time zone. Transfers,
 * adjustments, drafts and voided transactions never count. Exactly equal to the budget is "at limit", not over.
 */
@Service
public class BudgetService {

    /** A budget turns to WARNING at 80% of the amount (PF-BUD-2 default). */
    static final BigDecimal WARNING_FRACTION = new BigDecimal("0.8");

    public record Row(UUID id, UUID categoryId, BigDecimal amount, String currency, OffsetDateTime archivedAt, int version) {
    }

    public record View(UUID id, UUID categoryId, String categoryName, String amount, String currency, String spent, String remaining,
            String percentUsed, String status, int version) {
    }

    public record Summary(String month, LocalDate from, LocalDate to, List<View> items) {
    }

    public record CreateRequest(String categoryId, String amount, String currency) {
    }

    public record UpdateRequest(String amount, Integer version) {
    }

    private final JdbcClient jdbc;
    private final ReferenceService reference;
    private final FinanceAudit audit;

    public BudgetService(JdbcClient jdbc, ReferenceService reference, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.reference = reference;
        this.audit = audit;
    }

    private static OffsetDateTime now(FinanceContext ctx) {
        return OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC);
    }

    static YearMonth month(FinanceContext ctx, String raw) {
        if (raw == null || raw.isBlank()) {
            return YearMonth.from(ctx.today());
        }
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw Validate.invalid("month must look like 2026-03");
        }
    }

    public Summary summary(FinanceContext ctx, String monthRaw) {
        ctx.require(Action.READ);
        YearMonth ym = month(ctx, monthRaw);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        List<Row> rows = jdbc.sql("select id, category_id, amount, currency, archived_at, version from budgets where tenant_id = :t and archived_at is null order by currency, id")
                .param("t", ctx.tenantId()).query(Row.class).list();
        List<View> items = new ArrayList<>();
        for (Row r : rows) {
            items.add(view(ctx, r, from, to));
        }
        items.sort(java.util.Comparator.comparing((View v) -> v.categoryName() == null ? "" : v.categoryName().toLowerCase()).thenComparing(View::currency));
        return new Summary(ym.toString(), from, to, items);
    }

    private View view(FinanceContext ctx, Row r, LocalDate from, LocalDate to) {
        String catName = null;
        List<UUID> categories = null;
        if (r.categoryId() != null) {
            catName = reference.require(ctx.tenantId(), r.categoryId()).name();
            categories = reference.withChildren(ctx.tenantId(), r.categoryId());
        }
        BigDecimal spent = spent(ctx.tenantId(), r.currency(), categories, from, to);
        BigDecimal remaining = r.amount().subtract(spent);
        BigDecimal percent = spent.signum() <= 0 ? BigDecimal.ZERO : spent.multiply(BigDecimal.valueOf(100)).divide(r.amount(), 2, RoundingMode.HALF_EVEN);
        return new View(r.id(), r.categoryId(), catName, Currencies.format(r.amount(), r.currency()), r.currency(),
                Currencies.format(spent, r.currency()), Currencies.format(remaining, r.currency()), percent.setScale(2, RoundingMode.HALF_EVEN).toPlainString(),
                status(spent, r.amount()), r.version());
    }

    static String status(BigDecimal spent, BigDecimal amount) {
        if (spent.compareTo(amount) > 0) {
            return "OVER";
        }
        if (spent.compareTo(amount.multiply(WARNING_FRACTION)) >= 0) {
            return "WARNING";
        }
        return "UNDER";
    }

    BigDecimal spent(UUID tenant, String currency, List<UUID> categories, LocalDate from, LocalDate to) {
        String sql = "select coalesce(sum(case kind when 'EXPENSE' then amount else -amount end), 0) from transactions"
                + " where tenant_id = :t and status = 'POSTED' and kind in ('EXPENSE', 'REFUND') and currency = :c"
                + " and occurred_on between :f and :to" + (categories == null ? "" : " and category_id in (:cats)");
        var q = jdbc.sql(sql).param("t", tenant).param("c", currency).param("f", from).param("to", to);
        if (categories != null) {
            q = q.param("cats", categories);
        }
        return q.query(BigDecimal.class).single();
    }

    @Transactional
    public View create(FinanceContext ctx, CreateRequest req) {
        ctx.require(Action.CREATE);
        String currency = req.currency() == null ? null : req.currency().trim().toUpperCase(java.util.Locale.ROOT);
        if (!Currencies.supported(currency)) {
            throw Validate.invalid("currency is not supported");
        }
        BigDecimal amount = Amounts.parse("amount", req.amount(), currency, Amounts.Sign.POSITIVE);
        UUID categoryId = req.categoryId() == null || req.categoryId().isBlank() ? null
                : reference.requireUsable(ctx.tenantId(), Validate.uuid("category_id", req.categoryId(), true), "EXPENSE", "category_id").id();
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("insert into budgets (id, tenant_id, category_id, amount, currency, created_at, updated_at) values (:id, :t, :cat, :amt, :c, :now, :now)")
                    .param("id", id).param("t", ctx.tenantId()).param("cat", categoryId).param("amt", amount).param("c", currency)
                    .param("now", now(ctx)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "A budget for this category and currency already exists");
        }
        audit.record(ctx, "BUDGET_CREATED", "BUDGET", id, Currencies.format(amount, currency) + " " + currency);
        YearMonth ym = YearMonth.from(ctx.today());
        return view(ctx, load(ctx.tenantId(), id), ym.atDay(1), ym.atEndOfMonth());
    }

    private Row load(UUID tenant, UUID id) {
        return jdbc.sql("select id, category_id, amount, currency, archived_at, version from budgets where tenant_id = :t and id = :id")
                .param("t", tenant).param("id", id).query(Row.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    @Transactional
    public View update(FinanceContext ctx, UUID id, UpdateRequest req) {
        ctx.require(Action.UPDATE);
        int version = Validate.version(req.version());
        Row before = load(ctx.tenantId(), id);
        BigDecimal amount = Amounts.parse("amount", req.amount(), before.currency(), Amounts.Sign.POSITIVE);
        int updated = jdbc.sql("update budgets set amount = :a, version = version + 1, updated_at = :now where tenant_id = :t and id = :id and version = :v and archived_at is null")
                .param("a", amount).param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).param("v", version).update();
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "This budget was changed elsewhere (or archived); reload and try again");
        }
        audit.record(ctx, "BUDGET_UPDATED", "BUDGET", id, Currencies.format(before.amount(), before.currency()) + " -> " + Currencies.format(amount, before.currency()));
        YearMonth ym = YearMonth.from(ctx.today());
        return view(ctx, load(ctx.tenantId(), id), ym.atDay(1), ym.atEndOfMonth());
    }

    @Transactional
    public void archive(FinanceContext ctx, UUID id) {
        ctx.require(Action.ARCHIVE);
        load(ctx.tenantId(), id);
        jdbc.sql("update budgets set archived_at = :now, version = version + 1, updated_at = :now where tenant_id = :t and id = :id and archived_at is null")
                .param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx, "BUDGET_ARCHIVED", "BUDGET", id, null);
    }
}
