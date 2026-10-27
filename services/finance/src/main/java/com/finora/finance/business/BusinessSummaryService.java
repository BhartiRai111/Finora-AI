package com.finora.finance.business;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.core.Validate;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Deterministic, backend-computed business figures. Every figure is per currency; currencies are never added together or
 * converted. Definitions (also in docs/business-finance.md):
 * <ul>
 * <li><b>Invoiced (net)</b>: sum of (line amounts - discounts) of ISSUED, non-cancelled invoices dated in the period,
 * excluding tax. Tax shown as entered is reported separately; drafts and cancelled invoices are never counted.</li>
 * <li><b>Received</b>: payments that still stand (not voided) dated in the period, including any unallocated balance.</li>
 * <li><b>Expenses</b>: POSTED (not voided) business expenses dated in the period.</li>
 * <li><b>Net cash</b> = received - expenses. <b>Operating result</b> = invoiced net - expenses (accrual view, ex tax).</li>
 * <li><b>Receivables</b>: outstanding of issued invoices as of today; <b>overdue</b>: those past their due date today in the
 * business time zone; aging buckets by days past due.</li>
 * <li><b>Project profit</b> = invoiced net revenue - expenses posted to the project (same period filter on invoice date and
 * expense date); margin = profit / revenue, undefined (null) when revenue is zero.</li>
 * </ul>
 */
@Service
public class BusinessSummaryService {

    public record Bucket(String label, String amount) {
    }

    public record CategoryTotal(String category, String amount) {
    }

    public record CurrencySummary(String currency, String invoicedNet, String invoicedTax, String invoicedTotal, long invoiceCount, String received,
            String expenses, String netCash, String operatingResult, String receivables, String overdue, List<Bucket> aging, String unallocatedPayments,
            List<CategoryTotal> expensesByCategory) {
    }

    public record Overview(LocalDate from, LocalDate to, LocalDate asOf, List<CurrencySummary> currencies) {
    }

    public record Profitability(UUID projectId, String projectName, String currency, String budget, String costs, String budgetRemaining, String budgetUsedPercent,
            boolean overBudget, String revenueNet, String invoicedTotal, String received, String outstanding, String profit, String marginPercent, LocalDate from,
            LocalDate to) {
    }

    private record Money(String currency, BigDecimal a, BigDecimal b, BigDecimal c, long n) {
    }

    private record Recv(String currency, BigDecimal outstanding, BigDecimal overdue, BigDecimal current, BigDecimal d30, BigDecimal d60, BigDecimal d90, BigDecimal older) {
    }

    private record Cat(String currency, String name, BigDecimal amount) {
    }

    private final JdbcClient jdbc;
    private final ProjectLookup projects;

    public BusinessSummaryService(JdbcClient jdbc, ProjectLookup projects) {
        this.jdbc = jdbc;
        this.projects = projects;
    }

    private static BigDecimal z(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public Overview overview(BusinessContext ctx, String fromRaw, String toRaw) {
        ctx.requireReports();
        LocalDate today = ctx.today();
        LocalDate to = Validate.date("to", toRaw, false);
        LocalDate from = Validate.date("from", fromRaw, false);
        if (to == null) {
            to = today;
        }
        if (from == null) {
            from = to.withDayOfMonth(1);
        }
        if (from.isAfter(to)) {
            throw Validate.invalid("from cannot be after to");
        }
        if (ChronoUnit.DAYS.between(from, to) > 3660) {
            throw Validate.invalid("The period can span at most 10 years");
        }
        Map<String, CurrencySummaryBuilder> by = new TreeMap<>();
        UUID t = ctx.tenantId();
        for (Money m : jdbc.sql("""
                select currency, sum(subtotal - discount_total) as a, sum(tax_total) as b, sum(total) as c, count(*) as n from invoices
                where tenant_id = :t and status = 'ISSUED' and invoice_date between :f and :to group by currency
                """).param("t", t).param("f", from).param("to", to).query(Money.class).list()) {
            CurrencySummaryBuilder b = by.computeIfAbsent(m.currency().trim(), CurrencySummaryBuilder::new);
            b.invoicedNet = m.a();
            b.invoicedTax = m.b();
            b.invoicedTotal = m.c();
            b.count = m.n();
        }
        for (Money m : jdbc.sql("select currency, sum(amount) as a, null::numeric as b, null::numeric as c, count(*) as n from payments where tenant_id = :t and status = 'RECORDED' and payment_date between :f and :to group by currency")
                .param("t", t).param("f", from).param("to", to).query(Money.class).list()) {
            by.computeIfAbsent(m.currency().trim(), CurrencySummaryBuilder::new).received = m.a();
        }
        for (Money m : jdbc.sql("select currency, sum(amount) as a, null::numeric as b, null::numeric as c, count(*) as n from business_expenses where tenant_id = :t and status = 'POSTED' and expense_date between :f and :to group by currency")
                .param("t", t).param("f", from).param("to", to).query(Money.class).list()) {
            by.computeIfAbsent(m.currency().trim(), CurrencySummaryBuilder::new).expenses = m.a();
        }
        for (Cat c : jdbc.sql("""
                select e.currency, coalesce(c.name, 'Uncategorised') as name, sum(e.amount) as amount from business_expenses e
                left join expense_categories c on c.tenant_id = e.tenant_id and c.id = e.category_id
                where e.tenant_id = :t and e.status = 'POSTED' and e.expense_date between :f and :to group by e.currency, coalesce(c.name, 'Uncategorised')
                order by sum(e.amount) desc, 2
                """).param("t", t).param("f", from).param("to", to).query(Cat.class).list()) {
            String cur = c.currency().trim();
            by.computeIfAbsent(cur, CurrencySummaryBuilder::new).categories.add(new CategoryTotal(c.name(), Currencies.format(c.amount(), cur)));
        }
        for (Recv r : jdbc.sql("""
                select i.currency,
                       sum(i.total - pd.paid) as outstanding,
                       sum(case when i.due_date < :today then i.total - pd.paid else 0 end) as overdue,
                       sum(case when i.due_date >= :today then i.total - pd.paid else 0 end) as current,
                       sum(case when :today - i.due_date between 1 and 30 then i.total - pd.paid else 0 end) as d30,
                       sum(case when :today - i.due_date between 31 and 60 then i.total - pd.paid else 0 end) as d60,
                       sum(case when :today - i.due_date between 61 and 90 then i.total - pd.paid else 0 end) as d90,
                       sum(case when :today - i.due_date > 90 then i.total - pd.paid else 0 end) as older
                from invoices i
                """ + BizSql.PAID_LATERAL + """
                where i.tenant_id = :t and i.status = 'ISSUED' and i.total - pd.paid > 0 group by i.currency
                """).param("t", t).param("today", today).query(Recv.class).list()) {
            by.computeIfAbsent(r.currency().trim(), CurrencySummaryBuilder::new).recv = r;
        }
        for (Money m : jdbc.sql("""
                select p.currency, sum(p.amount - coalesce((select sum(a.amount) from payment_allocations a where a.tenant_id = p.tenant_id and a.payment_id = p.id), 0)) as a,
                       null::numeric as b, null::numeric as c, count(*) as n
                from payments p where p.tenant_id = :t and p.status = 'RECORDED' group by p.currency
                """).param("t", t).query(Money.class).list()) {
            by.computeIfAbsent(m.currency().trim(), CurrencySummaryBuilder::new).unallocated = m.a();
        }
        return new Overview(from, to, today, by.values().stream().map(CurrencySummaryBuilder::build).toList());
    }

    private static final class CurrencySummaryBuilder {
        final String currency;
        BigDecimal invoicedNet, invoicedTax, invoicedTotal, received, expenses, unallocated;
        long count;
        Recv recv;
        final List<CategoryTotal> categories = new ArrayList<>();

        CurrencySummaryBuilder(String currency) {
            this.currency = currency;
        }

        CurrencySummary build() {
            String c = currency;
            BigDecimal net = z(invoicedNet);
            BigDecimal rec = z(received);
            BigDecimal exp = z(expenses);
            Recv r = recv;
            List<Bucket> aging = r == null ? List.of() : List.of(new Bucket("Not yet due", Currencies.format(z(r.current()), c)),
                    new Bucket("1-30 days overdue", Currencies.format(z(r.d30()), c)), new Bucket("31-60 days overdue", Currencies.format(z(r.d60()), c)),
                    new Bucket("61-90 days overdue", Currencies.format(z(r.d90()), c)), new Bucket("Over 90 days overdue", Currencies.format(z(r.older()), c)));
            return new CurrencySummary(c, Currencies.format(net, c), Currencies.format(z(invoicedTax), c), Currencies.format(z(invoicedTotal), c), count,
                    Currencies.format(rec, c), Currencies.format(exp, c), Currencies.format(rec.subtract(exp), c), Currencies.format(net.subtract(exp), c),
                    Currencies.format(r == null ? BigDecimal.ZERO : z(r.outstanding()), c), Currencies.format(r == null ? BigDecimal.ZERO : z(r.overdue()), c),
                    aging, Currencies.format(z(unallocated).max(BigDecimal.ZERO), c), categories);
        }
    }

    // ---- projects ----

    /** Profitability of one project (its own currency), for an optional period. */
    Profitability profitability(BusinessContext ctx, ProjectService.Row p, LocalDate from, LocalDate to) {
        String cur = p.currency().trim();
        UUID t = ctx.tenantId();
        BigDecimal costs = jdbc.sql("""
                select coalesce(sum(amount), 0) from business_expenses where tenant_id = :t and project_id = :p and status = 'POSTED'
                and (cast(:f as date) is null or expense_date >= :f) and (cast(:to as date) is null or expense_date <= :to)
                """).param("t", t).param("p", p.id()).param("f", from, java.sql.Types.DATE).param("to", to, java.sql.Types.DATE).query(BigDecimal.class).single();
        record Inv(BigDecimal net, BigDecimal total, BigDecimal paid) {
        }
        Inv inv = jdbc.sql("""
                select coalesce(sum(i.subtotal - i.discount_total), 0) as net, coalesce(sum(i.total), 0) as total, coalesce(sum(pd.paid), 0) as paid
                from invoices i
                """ + BizSql.PAID_LATERAL + """
                where i.tenant_id = :t and i.project_id = :p and i.status = 'ISSUED'
                and (cast(:f as date) is null or i.invoice_date >= :f) and (cast(:to as date) is null or i.invoice_date <= :to)
                """).param("t", t).param("p", p.id()).param("f", from, java.sql.Types.DATE).param("to", to, java.sql.Types.DATE).query(Inv.class).single();
        BigDecimal profit = inv.net().subtract(costs);
        String margin = inv.net().signum() == 0 ? null : profit.multiply(BigDecimal.valueOf(100)).divide(inv.net(), 2, RoundingMode.HALF_EVEN).toPlainString();
        BigDecimal budget = p.budgetAmount();
        String used = budget == null ? null : costs.multiply(BigDecimal.valueOf(100)).divide(budget, 2, RoundingMode.HALF_EVEN).toPlainString();
        return new Profitability(p.id(), p.name(), cur, budget == null ? null : Currencies.format(budget, cur), Currencies.format(costs, cur),
                budget == null ? null : Currencies.format(budget.subtract(costs), cur), used, budget != null && costs.compareTo(budget) > 0,
                Currencies.format(inv.net(), cur), Currencies.format(inv.total(), cur), Currencies.format(inv.paid(), cur),
                Currencies.format(inv.total().subtract(inv.paid()), cur), Currencies.format(profit, cur), margin, from, to);
    }

    public Map<String, List<Profitability>> projectProfitability(BusinessContext ctx, String fromRaw, String toRaw, boolean includeArchived) {
        ctx.requireReports();
        LocalDate from = Validate.date("from", fromRaw, false);
        LocalDate to = Validate.date("to", toRaw, false);
        if (from != null && to != null && from.isAfter(to)) {
            throw Validate.invalid("from cannot be after to");
        }
        List<ProjectService.Row> rows = projects.all(ctx, includeArchived);
        if (rows.size() > 500) {
            throw new ApiException(ErrorCode.CONFLICT, "Too many projects for one summary; narrow it down");
        }
        return Map.of("items", rows.stream().map(r -> profitability(ctx, r, from, to)).toList());
    }

    public Profitability oneProject(BusinessContext ctx, UUID id, String fromRaw, String toRaw) {
        ctx.requireReports();
        LocalDate from = Validate.date("from", fromRaw, false);
        LocalDate to = Validate.date("to", toRaw, false);
        if (from != null && to != null && from.isAfter(to)) {
            throw Validate.invalid("from cannot be after to");
        }
        return profitability(ctx, projects.one(ctx, id), from, to);
    }
}
