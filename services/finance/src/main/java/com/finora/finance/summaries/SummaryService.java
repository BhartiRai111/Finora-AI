package com.finora.finance.summaries;

import com.finora.commons.authz.Action;
import com.finora.finance.accounts.AccountService;
import com.finora.finance.core.FinanceContext;
import com.finora.finance.core.Validate;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Deterministic summaries computed from posted transactions with exact decimals. Currencies are never mixed or converted
 * (no exchange-rate source exists; PD-09/CUR-2 are pending), so every figure is reported per currency.
 * Transfers and balance adjustments are not income or expense. Refunds reduce expenses, they are not income.
 */
@Service
public class SummaryService {

    public record CategoryLine(UUID categoryId, String name, String income, String expense) {
    }

    public record CurrencyCashflow(String currency, String income, String grossExpense, String refunds, String netExpense, String net,
            String transferFees, List<CategoryLine> categories) {
    }

    public record Cashflow(LocalDate from, LocalDate to, List<CurrencyCashflow> currencies) {
    }

    public record Component(UUID accountId, String name, String type, String currency, String balance, boolean liability, boolean archived) {
    }

    public record CurrencyNetWorth(String currency, String assets, String liabilities, String netWorth, List<Component> components) {
    }

    public record NetWorth(LocalDate asOf, List<CurrencyNetWorth> currencies) {
    }

    public record SeriesPoint(LocalDate date, List<Map<String, String>> currencies) {
    }

    public record Series(List<SeriesPoint> points) {
    }

    private record Totals(String currency, BigDecimal income, BigDecimal expense, BigDecimal refunds, BigDecimal fees) {
    }

    private record CategoryTotals(String currency, UUID categoryId, String name, BigDecimal income, BigDecimal expense) {
    }

    private final JdbcClient jdbc;
    private final AccountService accounts;

    public SummaryService(JdbcClient jdbc, AccountService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    public Cashflow cashflow(FinanceContext ctx, LocalDate from, LocalDate to) {
        ctx.require(Action.READ);
        LocalDate end = to == null ? ctx.today() : to;
        LocalDate start = from == null ? YearMonth.from(end).atDay(1) : from;
        if (start.isAfter(end)) {
            throw Validate.invalid("from must not be after to");
        }
        List<Totals> totals = jdbc.sql("""
                select currency,
                       coalesce(sum(amount) filter (where kind = 'INCOME'), 0)  as income,
                       coalesce(sum(amount) filter (where kind = 'EXPENSE'), 0) as expense,
                       coalesce(sum(amount) filter (where kind = 'REFUND'), 0)  as refunds,
                       coalesce(sum(amount - to_amount) filter (where kind = 'TRANSFER' and currency = to_currency), 0) as fees
                from transactions
                where tenant_id = :t and status = 'POSTED' and occurred_on between :f and :to
                group by currency order by currency
                """).param("t", ctx.tenantId()).param("f", start).param("to", end).query(Totals.class).list();
        List<CategoryTotals> categories = jdbc.sql("""
                select tx.currency, tx.category_id, c.name,
                       coalesce(sum(tx.amount) filter (where tx.kind = 'INCOME'), 0) as income,
                       coalesce(sum(case tx.kind when 'EXPENSE' then tx.amount when 'REFUND' then -tx.amount end), 0) as expense
                from transactions tx left join categories c on c.tenant_id = tx.tenant_id and c.id = tx.category_id
                where tx.tenant_id = :t and tx.status = 'POSTED' and tx.kind in ('INCOME', 'EXPENSE', 'REFUND') and tx.occurred_on between :f and :to
                group by tx.currency, tx.category_id, c.name order by tx.currency, c.name nulls last
                """).param("t", ctx.tenantId()).param("f", start).param("to", end).query(CategoryTotals.class).list();
        List<CurrencyCashflow> result = new ArrayList<>();
        for (Totals t : totals) {
            BigDecimal netExpense = t.expense().subtract(t.refunds());
            List<CategoryLine> lines = categories.stream().filter(c -> c.currency().equals(t.currency()))
                    .map(c -> new CategoryLine(c.categoryId(), c.name() == null ? null : c.name(), Currencies.format(c.income(), t.currency()),
                            Currencies.format(c.expense(), t.currency()))).toList();
            result.add(new CurrencyCashflow(t.currency(), Currencies.format(t.income(), t.currency()), Currencies.format(t.expense(), t.currency()),
                    Currencies.format(t.refunds(), t.currency()), Currencies.format(netExpense, t.currency()),
                    Currencies.format(t.income().subtract(netExpense), t.currency()), Currencies.format(t.fees(), t.currency()), lines));
        }
        return new Cashflow(start, end, result);
    }

    public NetWorth netWorth(FinanceContext ctx, LocalDate asOfRaw) {
        ctx.require(Action.READ);
        LocalDate asOf = asOfRaw == null ? ctx.today() : asOfRaw;
        if (asOf.isAfter(ctx.today())) {
            throw Validate.invalid("as_of cannot be in the future");
        }
        Map<UUID, AccountService.Row> rows = accounts.rows(ctx.tenantId(), true);
        return new NetWorth(asOf, netWorthAt(rows, accounts.balances(ctx.tenantId(), asOf, rows)));
    }

    private List<CurrencyNetWorth> netWorthAt(Map<UUID, AccountService.Row> rows, Map<UUID, BigDecimal> balances) {
        Map<String, List<AccountService.Row>> byCurrency = new TreeMap<>();
        for (AccountService.Row r : rows.values()) {
            if (balances.containsKey(r.id())) {
                byCurrency.computeIfAbsent(r.currency(), k -> new ArrayList<>()).add(r);
            }
        }
        List<CurrencyNetWorth> out = new ArrayList<>();
        for (Map.Entry<String, List<AccountService.Row>> e : byCurrency.entrySet()) {
            BigDecimal assets = BigDecimal.ZERO;
            BigDecimal liabilities = BigDecimal.ZERO;
            List<Component> components = new ArrayList<>();
            for (AccountService.Row r : e.getValue()) {
                BigDecimal b = balances.get(r.id());
                if (r.liability()) {
                    liabilities = liabilities.add(b);
                } else {
                    assets = assets.add(b);
                }
                // An archived account with nothing in it is history, not a component of today's net worth.
                if (r.archivedAt() == null || b.signum() != 0) {
                    components.add(new Component(r.id(), r.name(), r.type(), r.currency(), Currencies.format(b, r.currency()), r.liability(), r.archivedAt() != null));
                }
            }
            out.add(new CurrencyNetWorth(e.getKey(), Currencies.format(assets, e.getKey()), Currencies.format(liabilities, e.getKey()),
                    Currencies.format(assets.subtract(liabilities), e.getKey()), components));
        }
        return out;
    }

    /** Net worth at the end of each month in the range (the current month ends today). At most 60 points. */
    public Series series(FinanceContext ctx, LocalDate from, LocalDate to) {
        ctx.require(Action.READ);
        LocalDate end = to == null ? ctx.today() : to;
        LocalDate start = from == null ? end.minusMonths(11).withDayOfMonth(1) : from;
        if (start.isAfter(end) || end.isAfter(ctx.today())) {
            throw Validate.invalid("from must not be after to, and to cannot be in the future");
        }
        YearMonth first = YearMonth.from(start);
        YearMonth last = YearMonth.from(end);
        if (java.time.temporal.ChronoUnit.MONTHS.between(first, last) >= 60) {
            throw Validate.invalid("the range may cover at most 60 months");
        }
        Map<UUID, AccountService.Row> rows = accounts.rows(ctx.tenantId(), true);
        List<SeriesPoint> points = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            LocalDate date = m.atEndOfMonth().isAfter(end) ? end : m.atEndOfMonth();
            List<Map<String, String>> currencies = netWorthAt(rows, accounts.balances(ctx.tenantId(), date, rows)).stream()
                    .map(c -> Map.of("currency", c.currency(), "assets", c.assets(), "liabilities", c.liabilities(), "net_worth", c.netWorth())).toList();
            points.add(new SeriesPoint(date, currencies));
        }
        return new Series(points);
    }
}
