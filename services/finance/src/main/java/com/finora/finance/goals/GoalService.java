package com.finora.finance.goals;

import com.finora.commons.authz.Action;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.FinanceContext;
import com.finora.finance.core.Validate;
import com.finora.finance.money.Amounts;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Savings goals. Progress is the exact sum of the contributions the user records (withdrawals are negative); it is
 * independent of account balances. The required monthly saving is the remaining amount divided over the whole months left,
 * rounded UP to the currency's precision so that paying it every month always reaches the goal.
 */
@Service
public class GoalService {

    public record Row(UUID id, String name, BigDecimal targetAmount, String currency, LocalDate targetDate, OffsetDateTime archivedAt, int version) {
    }

    public record Contribution(UUID id, String amount, LocalDate contributedOn, String note) {
    }

    public record View(UUID id, String name, String targetAmount, String currency, LocalDate targetDate, String saved, String remaining,
            String percent, String status, Integer monthsLeft, String requiredMonthly, int version, List<Contribution> contributions) {
    }

    public record CreateRequest(String name, String targetAmount, String currency, String targetDate) {
    }

    public record UpdateRequest(String name, String targetAmount, String targetDate, Boolean clearTargetDate, Integer version) {
    }

    public record ContributionRequest(String amount, String contributedOn, String note) {
    }

    private record ContributionRow(UUID id, BigDecimal amount, LocalDate contributedOn, String note) {
    }

    private static final String COLS = "id, name, target_amount, currency, target_date, archived_at, version";

    private final JdbcClient jdbc;
    private final FinanceAudit audit;

    public GoalService(JdbcClient jdbc, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static OffsetDateTime now(FinanceContext ctx) {
        return OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC);
    }

    private Row row(UUID tenant, UUID id, boolean lock) {
        return jdbc.sql("select " + COLS + " from goals where tenant_id = :t and id = :id" + (lock ? " for update" : ""))
                .param("t", tenant).param("id", id).query(Row.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    private BigDecimal saved(UUID tenant, UUID goal) {
        return jdbc.sql("select coalesce(sum(amount), 0) from goal_contributions where tenant_id = :t and goal_id = :g")
                .param("t", tenant).param("g", goal).query(BigDecimal.class).single();
    }

    public List<View> list(FinanceContext ctx, boolean includeArchived) {
        ctx.require(Action.READ);
        return jdbc.sql("select " + COLS + " from goals where tenant_id = :t " + (includeArchived ? "" : "and archived_at is null ")
                + "order by archived_at is not null, target_date nulls last, lower(name), id").param("t", ctx.tenantId()).query(Row.class).list()
                .stream().map(r -> view(ctx, r, false)).toList();
    }

    public View get(FinanceContext ctx, UUID id) {
        ctx.require(Action.READ);
        return view(ctx, row(ctx.tenantId(), id, false), true);
    }

    static int monthsLeft(LocalDate today, LocalDate target) {
        long whole = ChronoUnit.MONTHS.between(today, target);
        return (int) Math.max(1, today.plusMonths(whole).isBefore(target) ? whole + 1 : whole);
    }

    private View view(FinanceContext ctx, Row r, boolean withContributions) {
        BigDecimal saved = saved(ctx.tenantId(), r.id());
        BigDecimal remaining = r.targetAmount().subtract(saved).max(BigDecimal.ZERO);
        BigDecimal percent = saved.signum() <= 0 ? BigDecimal.ZERO : saved.multiply(BigDecimal.valueOf(100)).divide(r.targetAmount(), 2, RoundingMode.HALF_EVEN);
        boolean achieved = saved.compareTo(r.targetAmount()) >= 0;
        String status = r.archivedAt() != null ? "ARCHIVED" : (achieved ? "ACHIEVED" : "IN_PROGRESS");
        Integer months = null;
        String required = null;
        if (r.targetDate() != null && !achieved && r.archivedAt() == null) {
            LocalDate today = ctx.today();
            months = r.targetDate().isAfter(today) ? monthsLeft(today, r.targetDate()) : 0;
            // Overdue: everything that is left is due now.
            BigDecimal per = months == 0 ? remaining : remaining.divide(BigDecimal.valueOf(months), Currencies.exponentOrThrow(r.currency()), RoundingMode.CEILING);
            required = Currencies.format(per, r.currency());
        }
        List<Contribution> contributions = withContributions ? jdbc.sql("""
                select id, amount, contributed_on, note from goal_contributions where tenant_id = :t and goal_id = :g
                order by contributed_on desc, created_at desc, id limit 200
                """).param("t", ctx.tenantId()).param("g", r.id()).query(ContributionRow.class).list().stream()
                .map(c -> new Contribution(c.id(), Currencies.format(c.amount(), r.currency()), c.contributedOn(), c.note())).toList() : null;
        return new View(r.id(), r.name(), Currencies.format(r.targetAmount(), r.currency()), r.currency(), r.targetDate(),
                Currencies.format(saved, r.currency()), Currencies.format(remaining, r.currency()), percent.toPlainString(), status, months, required,
                r.version(), contributions);
    }

    @Transactional
    public View create(FinanceContext ctx, CreateRequest req) {
        ctx.require(Action.CREATE);
        String name = Validate.text("name", req.name(), 1, 100, true);
        String currency = req.currency() == null ? null : req.currency().trim().toUpperCase(Locale.ROOT);
        if (!Currencies.supported(currency)) {
            throw Validate.invalid("currency is not supported");
        }
        BigDecimal target = Amounts.parse("target_amount", req.targetAmount(), currency, Amounts.Sign.POSITIVE);
        LocalDate date = Validate.date("target_date", req.targetDate(), false);
        if (date != null && date.isBefore(ctx.today())) {
            throw Validate.invalid("target_date cannot be in the past");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into goals (id, tenant_id, name, target_amount, currency, target_date, created_at, updated_at) values (:id, :t, :n, :a, :c, :d, :now, :now)")
                .param("id", id).param("t", ctx.tenantId()).param("n", name).param("a", target).param("c", currency).param("d", date)
                .param("now", now(ctx)).update();
        audit.record(ctx, "GOAL_CREATED", "GOAL", id, Currencies.format(target, currency) + " " + currency);
        return view(ctx, row(ctx.tenantId(), id, false), true);
    }

    @Transactional
    public View update(FinanceContext ctx, UUID id, UpdateRequest req) {
        ctx.require(Action.UPDATE);
        int version = Validate.version(req.version());
        Row r = row(ctx.tenantId(), id, true);
        if (r.version() != version) {
            throw new ApiException(ErrorCode.CONFLICT, "This goal was changed elsewhere; reload and try again");
        }
        String name = req.name() == null ? r.name() : Validate.text("name", req.name(), 1, 100, true);
        BigDecimal target = req.targetAmount() == null ? r.targetAmount() : Amounts.parse("target_amount", req.targetAmount(), r.currency(), Amounts.Sign.POSITIVE);
        LocalDate date = Boolean.TRUE.equals(req.clearTargetDate()) ? null : (req.targetDate() == null ? r.targetDate() : Validate.date("target_date", req.targetDate(), true));
        jdbc.sql("update goals set name = :n, target_amount = :a, target_date = :d, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                .param("n", name).param("a", target).param("d", date).param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx, "GOAL_UPDATED", "GOAL", id, "target " + Currencies.format(r.targetAmount(), r.currency()) + " -> " + Currencies.format(target, r.currency()));
        return view(ctx, row(ctx.tenantId(), id, false), true);
    }

    @Transactional
    public View setArchived(FinanceContext ctx, UUID id, boolean archived) {
        ctx.require(Action.ARCHIVE);
        row(ctx.tenantId(), id, true);
        jdbc.sql("update goals set archived_at = :a, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                .param("a", archived ? now(ctx) : null).param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx, archived ? "GOAL_ARCHIVED" : "GOAL_RESTORED", "GOAL", id, null);
        return view(ctx, row(ctx.tenantId(), id, false), true);
    }

    @Transactional
    public View contribute(FinanceContext ctx, UUID id, ContributionRequest req) {
        ctx.require(Action.CREATE);
        Row r = row(ctx.tenantId(), id, true);
        if (r.archivedAt() != null) {
            throw new ApiException(ErrorCode.CONFLICT, "This goal is archived");
        }
        BigDecimal amount = Amounts.parse("amount", req.amount(), r.currency(), Amounts.Sign.NON_ZERO);
        LocalDate date = Validate.date("contributed_on", req.contributedOn(), false);
        if (date == null) {
            date = ctx.today();
        }
        if (date.isAfter(ctx.today())) {
            throw Validate.invalid("contributed_on cannot be in the future");
        }
        String note = Validate.text("note", req.note(), 1, 500, false);
        if (saved(ctx.tenantId(), id).add(amount).signum() < 0) {
            throw Validate.invalid("a withdrawal cannot take the saved amount below zero");
        }
        UUID cid = UUID.randomUUID();
        jdbc.sql("insert into goal_contributions (id, tenant_id, goal_id, amount, contributed_on, note, created_at) values (:id, :t, :g, :a, :d, :n, :now)")
                .param("id", cid).param("t", ctx.tenantId()).param("g", id).param("a", amount).param("d", date).param("n", note)
                .param("now", now(ctx)).update();
        audit.record(ctx, amount.signum() > 0 ? "GOAL_CONTRIBUTION" : "GOAL_WITHDRAWAL", "GOAL", id, Currencies.format(amount, r.currency()) + " on " + date);
        return view(ctx, r, true);
    }
}
