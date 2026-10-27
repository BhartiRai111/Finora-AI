package com.finora.finance.insights;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.budgets.BudgetService;
import com.finora.finance.business.BusinessSummaryService;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.Validate;
import com.finora.finance.insights.ReportModel.Comparison;
import com.finora.finance.insights.ReportModel.CurrencyComparison;
import com.finora.finance.insights.ReportModel.CurrencyReport;
import com.finora.finance.insights.ReportModel.Delta;
import com.finora.finance.insights.ReportModel.Report;
import com.finora.finance.insights.ReportModel.Row;
import com.finora.finance.money.Currencies;
import com.finora.finance.summaries.SummaryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Reports. Every figure is computed here with exact decimal arithmetic from the finance service's own tables, using the SAME definitions as
 * the Step 17/18 summaries (documented in docs/reports-and-imports.md). Nothing is converted between currencies: each currency is its own
 * section. Excluded everywhere: drafts, cancelled invoices, voided payments, voided expenses and voided transactions; transfers are not
 * income or expense; refunds reduce spending; partial payments count when they are received (cash) while invoices count when issued (accrual).
 */
@Service
public class ReportService {

    public enum Granularity { MONTH, WEEK }

    public record Params(String from, String to, String granularity, String compare, String compareFrom, String compareTo, String month, String asOf) {
    }

    public static final List<String> PERSONAL_REPORTS = List.of("cash-flow", "budget-vs-actual", "net-worth");
    public static final List<String> BUSINESS_REPORTS = List.of("business-performance", "receivables", "receivables-by-client", "project-profitability");

    private static final int MAX_BUCKETS = 120;

    /** Payments that stand and are dated on or before the as-of date (so the receivables report can look at an earlier day). */
    private static final String PAID_AS_OF = """
            left join lateral (select coalesce(sum(a.amount), 0) as paid from payment_allocations a
                               join payments p on p.tenant_id = a.tenant_id and p.id = a.payment_id
                               where a.tenant_id = i.tenant_id and a.invoice_id = i.id and p.status = 'RECORDED' and p.payment_date <= :today) pd on true
            """;


    static final class Bucket {
        final Map<String, BigDecimal> values = new LinkedHashMap<>();
        /** Which sources had at least one record in this bucket (income, expense, refund / invoices, payments, expenses). */
        final java.util.Set<String> sources = new java.util.HashSet<>();
        boolean activity;
    }

    private record PersonalRow(String currency, LocalDate bucket, BigDecimal income, BigDecimal expense, BigDecimal refunds, long incomes, long expenses, long refundCount) {
    }

    private record AmountRow(String currency, LocalDate bucket, BigDecimal amount) {
    }

    private record AgingRow(String currency, BigDecimal outstanding, BigDecimal overdue, BigDecimal current, BigDecimal d30, BigDecimal d60, BigDecimal d90,
            BigDecimal older, long invoices) {
    }

    private record ClientRow(String currency, String client, BigDecimal outstanding, BigDecimal overdue, long invoices) {
    }

    private final JdbcClient jdbc;
    private final BudgetService budgets;
    private final SummaryService summaries;
    private final BusinessSummaryService business;
    private final FinanceAudit audit;

    public ReportService(JdbcClient jdbc, BudgetService budgets, SummaryService summaries, BusinessSummaryService business, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.budgets = budgets;
        this.summaries = summaries;
        this.business = business;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- entry points

    public Report report(InsightsContext ctx, String name, Params p) {
        ctx.requireReports();
        List<String> allowed = ctx.business() ? BUSINESS_REPORTS : PERSONAL_REPORTS;
        if (!allowed.contains(name)) {
            throw new ApiException(ErrorCode.NOT_FOUND, null);
        }
        return switch (name) {
            case "cash-flow" -> series(ctx, name, "Income, expenses and net cash flow", new String[] {"income", "expense", "refunds", "net_expense", "net"}, p);
            case "business-performance" -> series(ctx, name, "Business performance", new String[] {"invoiced_net", "received", "expenses", "operating_result", "net_cash"}, p);
            case "budget-vs-actual" -> budgetVsActual(ctx, p);
            case "net-worth" -> netWorth(ctx, p);
            case "receivables" -> receivables(ctx, p, false);
            case "receivables-by-client" -> receivables(ctx, p, true);
            default -> projects(ctx, p);
        };
    }

    /** CSV of the same report; needs the export right. Cells with user-controlled text are formula-protected. */
    public String exportCsv(InsightsContext ctx, String name, Params p) {
        ctx.requireExport();
        Report r = report(ctx, name, p);
        StringBuilder out = new StringBuilder();
        for (CurrencyReport c : r.currencies()) {
            List<String> head = new ArrayList<>(List.of("currency", "period", "start", "end", "activity"));
            head.addAll(c.columns());
            Csv.row(out, head);
            for (Row row : c.rows()) {
                List<String> cells = new ArrayList<>(List.of(c.currency(), row.period(), row.start() == null ? "" : row.start().toString(),
                        row.end() == null ? "" : row.end().toString(), String.valueOf(row.activity())));
                c.columns().forEach(col -> cells.add(row.values().getOrDefault(col, "")));
                Csv.row(out, cells);
            }
            if (!c.totals().isEmpty()) {
                List<String> cells = new ArrayList<>(List.of(c.currency(), "TOTAL", r.from() == null ? "" : r.from().toString(), r.to() == null ? "" : r.to().toString(), ""));
                c.columns().forEach(col -> cells.add(c.totals().getOrDefault(col, "")));
                Csv.row(out, cells);
            }
        }
        if (r.comparison() != null) {
            for (CurrencyComparison cc : r.comparison().currencies()) {
                List<String> columns = new ArrayList<>(cc.current().keySet());
                List<String> head = new ArrayList<>(List.of("currency", "comparison", "from", "to"));
                head.addAll(columns);
                Csv.row(out, head);
                for (String[] line : new String[][] {{"CURRENT", r.from().toString(), r.to().toString()}, {"PREVIOUS", r.comparison().from().toString(), r.comparison().to().toString()},
                        {"CHANGE_AMOUNT", "", ""}, {"CHANGE_PERCENT", "", ""}}) {
                    List<String> cells = new ArrayList<>(List.of(cc.currency(), line[0], line[1], line[2]));
                    for (String col : columns) {
                        cells.add(switch (line[0]) {
                            case "CURRENT" -> cc.current().get(col);
                            case "PREVIOUS" -> cc.previous().get(col);
                            case "CHANGE_AMOUNT" -> cc.change().get(col).amount();
                            default -> cc.change().get(col).percent() == null ? "" : cc.change().get(col).percent();
                        });
                    }
                    Csv.row(out, cells);
                }
            }
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "REPORT_EXPORTED", "REPORT", name, "from " + r.from() + " to " + r.to());
        return out.toString();
    }

    public static String fileName(String name, Report r) {
        return name + "-" + (r.from() == null ? "" : r.from()) + "-" + (r.to() == null ? "" : r.to()) + ".csv";
    }

    // ---------------------------------------------------------------- periods

    private record Range(LocalDate from, LocalDate to) {
        long days() {
            return ChronoUnit.DAYS.between(from, to) + 1;
        }
    }

    private Range range(InsightsContext ctx, String fromRaw, String toRaw) {
        LocalDate to = Validate.date("to", toRaw, false);
        LocalDate from = Validate.date("from", fromRaw, false);
        if (to == null) {
            to = ctx.today();
        }
        if (from == null) {
            from = to.withDayOfMonth(1).minusMonths(5);
        }
        if (from.isAfter(to)) {
            throw Validate.invalid("from cannot be after to");
        }
        if (ChronoUnit.DAYS.between(from, to) > 3660) {
            throw Validate.invalid("The period can span at most 10 years");
        }
        return new Range(from, to);
    }

    private static Granularity granularity(String raw) {
        if (raw == null || raw.isBlank()) {
            return Granularity.MONTH;
        }
        try {
            return Granularity.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw Validate.invalid("granularity must be month or week");
        }
    }

    private static LocalDate bucketStart(LocalDate d, Granularity g) {
        return g == Granularity.MONTH ? d.withDayOfMonth(1) : d.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
    }

    private static LocalDate bucketEnd(LocalDate start, Granularity g) {
        return g == Granularity.MONTH ? YearMonth.from(start).atEndOfMonth() : start.plusDays(6);
    }

    private static String label(LocalDate start, Granularity g) {
        return g == Granularity.MONTH ? YearMonth.from(start).toString() : "%d-W%02d".formatted(start.get(IsoFields.WEEK_BASED_YEAR), start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private static List<LocalDate> buckets(Range r, Granularity g) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate b = bucketStart(r.from(), g); !b.isAfter(r.to()); b = g == Granularity.MONTH ? b.plusMonths(1) : b.plusWeeks(1)) {
            out.add(b);
        }
        if (out.size() > MAX_BUCKETS) {
            throw Validate.invalid("That range has " + out.size() + " periods; choose a shorter range or monthly granularity (at most " + MAX_BUCKETS + ")");
        }
        return out;
    }

    // ---------------------------------------------------------------- metrics engine (also used by the forecast)

    /** currency -> bucket start -> column values and whether anything happened in that bucket. Missing buckets are absent. */
    Map<String, TreeMap<LocalDate, Bucket>> metrics(InsightsContext ctx, String name, LocalDate from, LocalDate to, Granularity g) {
        Map<String, TreeMap<LocalDate, Bucket>> by = new TreeMap<>();
        String trunc = g == Granularity.MONTH ? "month" : "week";
        if (!ctx.business()) {
            for (PersonalRow r : jdbc.sql("""
                    select currency, date_trunc('%s', occurred_on)::date as bucket,
                           coalesce(sum(amount) filter (where kind = 'INCOME'), 0) as income,
                           coalesce(sum(amount) filter (where kind = 'EXPENSE'), 0) as expense,
                           coalesce(sum(amount) filter (where kind = 'REFUND'), 0) as refunds,
                           count(*) filter (where kind = 'INCOME') as incomes, count(*) filter (where kind = 'EXPENSE') as expenses, count(*) filter (where kind = 'REFUND') as refund_count
                    from transactions
                    where tenant_id = :t and status = 'POSTED' and kind in ('INCOME', 'EXPENSE', 'REFUND') and occurred_on between :f and :to
                    group by currency, 2
                    """.formatted(trunc)).param("t", ctx.tenantId()).param("f", from).param("to", to).query(PersonalRow.class).list()) {
                Bucket b = bucket(by, r.currency(), r.bucket());
                b.activity = true;
                if (r.incomes() > 0) {
                    b.sources.add("income");
                }
                if (r.expenses() > 0) {
                    b.sources.add("expense");
                }
                if (r.refundCount() > 0) {
                    b.sources.add("refund");
                }
                b.values.put("income", r.income());
                b.values.put("expense", r.expense());
                b.values.put("refunds", r.refunds());
                b.values.put("net_expense", r.expense().subtract(r.refunds()));
                b.values.put("net", r.income().subtract(r.expense().subtract(r.refunds())));
            }
            return by;
        }
        collect(by, "invoiced_net", "invoices", jdbc.sql("""
                select currency, date_trunc('%s', invoice_date)::date as bucket, sum(subtotal - discount_total) as amount from invoices
                where tenant_id = :t and status = 'ISSUED' and invoice_date between :f and :to group by currency, 2
                """.formatted(trunc)).param("t", ctx.tenantId()).param("f", from).param("to", to).query(AmountRow.class).list());
        collect(by, "received", "payments", jdbc.sql("""
                select currency, date_trunc('%s', payment_date)::date as bucket, sum(amount) as amount from payments
                where tenant_id = :t and status = 'RECORDED' and payment_date between :f and :to group by currency, 2
                """.formatted(trunc)).param("t", ctx.tenantId()).param("f", from).param("to", to).query(AmountRow.class).list());
        collect(by, "expenses", "expenses", jdbc.sql("""
                select currency, date_trunc('%s', expense_date)::date as bucket, sum(amount) as amount from business_expenses
                where tenant_id = :t and status = 'POSTED' and expense_date between :f and :to group by currency, 2
                """.formatted(trunc)).param("t", ctx.tenantId()).param("f", from).param("to", to).query(AmountRow.class).list());
        for (TreeMap<LocalDate, Bucket> months : by.values()) {
            for (Bucket b : months.values()) {
                for (String col : new String[] {"invoiced_net", "received", "expenses"}) {
                    b.values.putIfAbsent(col, BigDecimal.ZERO);
                }
                b.values.put("operating_result", b.values.get("invoiced_net").subtract(b.values.get("expenses")));
                b.values.put("net_cash", b.values.get("received").subtract(b.values.get("expenses")));
            }
        }
        return by;
    }

    private static Bucket bucket(Map<String, TreeMap<LocalDate, Bucket>> by, String currency, LocalDate start) {
        return by.computeIfAbsent(currency.trim(), c -> new TreeMap<>()).computeIfAbsent(start, s -> new Bucket());
    }

    private static void collect(Map<String, TreeMap<LocalDate, Bucket>> by, String column, String source, List<AmountRow> rows) {
        for (AmountRow r : rows) {
            Bucket b = bucket(by, r.currency(), r.bucket());
            b.activity = true;
            b.sources.add(source);
            b.values.put(column, r.amount());
        }
    }

    /** The monthly value of one metric per currency for complete months, used by the forecast. */
    Map<String, TreeMap<LocalDate, Bucket>> monthly(InsightsContext ctx, LocalDate from, LocalDate to) {
        return metrics(ctx, ctx.business() ? "business-performance" : "cash-flow", from, to, Granularity.MONTH);
    }

    static BigDecimal value(Bucket b, String column) {
        return b.values.getOrDefault(column, BigDecimal.ZERO);
    }

    /** True when the metric's own source records exist in the bucket (a month with only expenses is not a month of invoicing). */
    static boolean activity(Bucket b, String metric) {
        return switch (metric) {
            case "income" -> b.sources.contains("income");
            case "expense" -> b.sources.contains("expense");
            case "net_expense" -> b.sources.contains("expense") || b.sources.contains("refund");
            case "invoiced_net" -> b.sources.contains("invoices");
            case "received" -> b.sources.contains("payments");
            case "expenses" -> b.sources.contains("expenses");
            case "net_cash" -> b.sources.contains("payments") || b.sources.contains("expenses");
            case "operating_result" -> b.sources.contains("invoices") || b.sources.contains("expenses");
            default -> b.activity;
        };
    }

    // ---------------------------------------------------------------- time-series reports

    private Report series(InsightsContext ctx, String name, String title, String[] columnArray, Params p) {
        Range range = range(ctx, p.from(), p.to());
        Granularity g = granularity(p.granularity());
        List<LocalDate> buckets = buckets(range, g);
        List<String> columns = List.of(columnArray);
        Map<String, TreeMap<LocalDate, Bucket>> data = metrics(ctx, name, range.from(), range.to(), g);
        List<CurrencyReport> out = new ArrayList<>();
        for (Map.Entry<String, TreeMap<LocalDate, Bucket>> e : data.entrySet()) {
            String cur = e.getKey();
            Map<String, BigDecimal> totals = new LinkedHashMap<>();
            columns.forEach(c -> totals.put(c, BigDecimal.ZERO));
            List<Row> rows = new ArrayList<>();
            for (LocalDate b : buckets) {
                Bucket bucket = e.getValue().get(b);
                Map<String, String> values = new LinkedHashMap<>();
                for (String col : columns) {
                    BigDecimal v = bucket == null ? BigDecimal.ZERO : value(bucket, col);
                    values.put(col, Currencies.format(v, cur));
                    totals.merge(col, v, BigDecimal::add);
                }
                LocalDate start = b.isBefore(range.from()) ? range.from() : b;
                LocalDate end = bucketEnd(b, g).isAfter(range.to()) ? range.to() : bucketEnd(b, g);
                rows.add(new Row(label(b, g), start, end, bucket != null && bucket.activity, values));
            }
            out.add(new CurrencyReport(cur, columns, rows, format(totals, cur)));
        }
        Comparison comparison = comparison(ctx, name, columns, range, p, out);
        return new Report(name, title, ctx.domain().name(), range.from(), range.to(), g.name(), definitions(name), notes(name), out, comparison);
    }

    private Comparison comparison(InsightsContext ctx, String name, List<String> columns, Range range, Params p, List<CurrencyReport> current) {
        boolean previous = "previous".equalsIgnoreCase(p.compare());
        boolean custom = p.compareFrom() != null || p.compareTo() != null;
        if (!previous && !custom) {
            return null;
        }
        Range other;
        if (custom) {
            LocalDate f = Validate.date("compare_from", p.compareFrom(), true);
            LocalDate t = Validate.date("compare_to", p.compareTo(), true);
            if (f.isAfter(t) || ChronoUnit.DAYS.between(f, t) > 3660) {
                throw Validate.invalid("compare_from must not be after compare_to, within 10 years");
            }
            other = new Range(f, t);
        } else {
            LocalDate t = range.from().minusDays(1);
            other = new Range(t.minusDays(range.days() - 1), t);
        }
        Map<String, TreeMap<LocalDate, Bucket>> data = metrics(ctx, name, other.from(), other.to(), Granularity.MONTH);
        Map<String, Map<String, BigDecimal>> previousTotals = new TreeMap<>();
        for (var e : data.entrySet()) {
            Map<String, BigDecimal> totals = new LinkedHashMap<>();
            columns.forEach(c -> totals.put(c, BigDecimal.ZERO));
            e.getValue().values().forEach(b -> columns.forEach(c -> totals.merge(c, value(b, c), BigDecimal::add)));
            previousTotals.put(e.getKey(), totals);
        }
        Map<String, Map<String, String>> currentTotals = new TreeMap<>();
        current.forEach(c -> currentTotals.put(c.currency(), c.totals()));
        List<String> currencies = new ArrayList<>(new java.util.TreeSet<>(java.util.stream.Stream.concat(currentTotals.keySet().stream(), previousTotals.keySet().stream()).toList()));
        List<CurrencyComparison> out = new ArrayList<>();
        for (String cur : currencies) {
            Map<String, String> cur1 = new LinkedHashMap<>();
            Map<String, String> prev1 = new LinkedHashMap<>();
            Map<String, Delta> change = new LinkedHashMap<>();
            for (String col : columns) {
                BigDecimal a = new BigDecimal(currentTotals.getOrDefault(cur, Map.of()).getOrDefault(col, Currencies.format(BigDecimal.ZERO, cur)));
                BigDecimal b = previousTotals.containsKey(cur) ? previousTotals.get(cur).get(col) : BigDecimal.ZERO;
                cur1.put(col, Currencies.format(a, cur));
                prev1.put(col, Currencies.format(b, cur));
                BigDecimal d = a.subtract(b);
                String percent = b.signum() == 0 ? null : d.multiply(BigDecimal.valueOf(100)).divide(b.abs(), 2, RoundingMode.HALF_EVEN).toPlainString();
                change.put(col, new Delta(Currencies.format(d, cur), percent));
            }
            out.add(new CurrencyComparison(cur, cur1, prev1, change));
        }
        return new Comparison(other.from(), other.to(), out);
    }

    private static Map<String, String> format(Map<String, BigDecimal> totals, String currency) {
        Map<String, String> out = new LinkedHashMap<>();
        totals.forEach((k, v) -> out.put(k, Currencies.format(v, currency)));
        return out;
    }

    // ---------------------------------------------------------------- personal-only reports

    private Report budgetVsActual(InsightsContext ctx, Params p) {
        if (p.compare() != null || p.compareFrom() != null) {
            throw Validate.invalid("Comparison is not available for this report");
        }
        BudgetService.Summary s = budgets.summary(ctx.personal(), p.month());
        Map<String, List<Row>> rows = new TreeMap<>();
        for (BudgetService.View v : s.items()) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("budget", v.amount());
            values.put("spent", v.spent());
            values.put("remaining", v.remaining());
            values.put("percent_used", v.percentUsed());
            values.put("status", v.status());
            rows.computeIfAbsent(v.currency(), c -> new ArrayList<>()).add(new Row(v.categoryName() == null ? "All spending" : v.categoryName(), s.from(), s.to(), true, values));
        }
        List<CurrencyReport> out = rows.entrySet().stream()
                .map(e -> new CurrencyReport(e.getKey(), List.of("budget", "spent", "remaining", "percent_used", "status"), e.getValue(), Map.of())).toList();
        return new Report("budget-vs-actual", "Budget versus actual", "PERSONAL", s.from(), s.to(), "MONTH", definitions("budget-vs-actual"), notes("budget-vs-actual"), out, null);
    }

    private Report netWorth(InsightsContext ctx, Params p) {
        if (p.compare() != null || p.compareFrom() != null) {
            throw Validate.invalid("Comparison is not available for this report");
        }
        SummaryService.Series s = summaries.series(ctx.personal(), Validate.date("from", p.from(), false), Validate.date("to", p.to(), false));
        Map<String, List<Row>> rows = new TreeMap<>();
        for (SummaryService.SeriesPoint pt : s.points()) {
            for (Map<String, String> c : pt.currencies()) {
                Map<String, String> values = new LinkedHashMap<>();
                values.put("assets", c.get("assets"));
                values.put("liabilities", c.get("liabilities"));
                values.put("net_worth", c.get("net_worth"));
                rows.computeIfAbsent(c.get("currency"), k -> new ArrayList<>()).add(new Row(YearMonth.from(pt.date()).toString(), pt.date(), pt.date(), true, values));
            }
        }
        List<CurrencyReport> out = rows.entrySet().stream().map(e -> new CurrencyReport(e.getKey(), List.of("assets", "liabilities", "net_worth"), e.getValue(), Map.of())).toList();
        LocalDate from = s.points().isEmpty() ? null : s.points().get(0).date();
        LocalDate to = s.points().isEmpty() ? null : s.points().get(s.points().size() - 1).date();
        return new Report("net-worth", "Net worth at month end", "PERSONAL", from, to, "MONTH", definitions("net-worth"), notes("net-worth"), out, null);
    }

    // ---------------------------------------------------------------- business-only reports

    private Report receivables(InsightsContext ctx, Params p, boolean byClient) {
        if (p.compare() != null || p.compareFrom() != null) {
            throw Validate.invalid("Comparison is not available for this report");
        }
        LocalDate asOf = Validate.date("as_of", p.asOf(), false);
        LocalDate today = asOf == null ? ctx.today() : asOf;
        if (today.isAfter(ctx.today())) {
            throw Validate.invalid("as_of cannot be in the future");
        }
        List<CurrencyReport> out = new ArrayList<>();
        if (byClient) {
            Map<String, List<Row>> rows = new TreeMap<>();
            Map<String, BigDecimal[]> totals = new TreeMap<>();
            for (ClientRow r : jdbc.sql("""
                    select i.currency, c.name as client, sum(i.total - pd.paid) as outstanding,
                           sum(case when i.due_date < :today then i.total - pd.paid else 0 end) as overdue, count(*) as invoices
                    from invoices i join clients c on c.tenant_id = i.tenant_id and c.id = i.client_id
                    """ + PAID_AS_OF + """
                    where i.tenant_id = :t and i.status = 'ISSUED' and i.invoice_date <= :today and i.total - pd.paid > 0
                    group by i.currency, c.name order by i.currency, sum(i.total - pd.paid) desc, c.name limit 500
                    """).param("t", ctx.tenantId()).param("today", today).query(ClientRow.class).list()) {
                String cur = r.currency().trim();
                Map<String, String> v = new LinkedHashMap<>();
                v.put("outstanding", Currencies.format(r.outstanding(), cur));
                v.put("overdue", Currencies.format(r.overdue(), cur));
                v.put("invoices", String.valueOf(r.invoices()));
                rows.computeIfAbsent(cur, k -> new ArrayList<>()).add(new Row(r.client(), null, null, true, v));
                BigDecimal[] t = totals.computeIfAbsent(cur, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                t[0] = t[0].add(r.outstanding());
                t[1] = t[1].add(r.overdue());
            }
            rows.forEach((cur, list) -> out.add(new CurrencyReport(cur, List.of("outstanding", "overdue", "invoices"), list,
                    Map.of("outstanding", Currencies.format(totals.get(cur)[0], cur), "overdue", Currencies.format(totals.get(cur)[1], cur)))));
            return new Report("receivables-by-client", "Receivables by client", "BUSINESS", null, today, null, definitions("receivables-by-client"), notes("receivables-by-client"), out, null);
        }
        for (AgingRow r : jdbc.sql("""
                select i.currency, sum(i.total - pd.paid) as outstanding,
                       sum(case when i.due_date < :today then i.total - pd.paid else 0 end) as overdue,
                       sum(case when i.due_date >= :today then i.total - pd.paid else 0 end) as current,
                       sum(case when :today - i.due_date between 1 and 30 then i.total - pd.paid else 0 end) as d30,
                       sum(case when :today - i.due_date between 31 and 60 then i.total - pd.paid else 0 end) as d60,
                       sum(case when :today - i.due_date between 61 and 90 then i.total - pd.paid else 0 end) as d90,
                       sum(case when :today - i.due_date > 90 then i.total - pd.paid else 0 end) as older, count(*) as invoices
                from invoices i
                """ + PAID_AS_OF + """
                where i.tenant_id = :t and i.status = 'ISSUED' and i.invoice_date <= :today and i.total - pd.paid > 0 group by i.currency order by i.currency
                """).param("t", ctx.tenantId()).param("today", today).query(AgingRow.class).list()) {
            String cur = r.currency().trim();
            List<Row> rows = new ArrayList<>();
            String[] labels = {"Not yet due", "1-30 days overdue", "31-60 days overdue", "61-90 days overdue", "Over 90 days overdue"};
            BigDecimal[] amounts = {r.current(), r.d30(), r.d60(), r.d90(), r.older()};
            for (int i = 0; i < labels.length; i++) {
                rows.add(new Row(labels[i], null, null, amounts[i].signum() != 0, new LinkedHashMap<>(Map.of("amount", Currencies.format(amounts[i], cur)))));
            }
            out.add(new CurrencyReport(cur, List.of("amount"), rows, new LinkedHashMap<>(Map.of("outstanding", Currencies.format(r.outstanding(), cur),
                    "overdue", Currencies.format(r.overdue(), cur), "invoices", String.valueOf(r.invoices())))));
        }
        return new Report("receivables", "Receivables aging", "BUSINESS", null, today, null, definitions("receivables"), notes("receivables"), out, null);
    }

    private Report projects(InsightsContext ctx, Params p) {
        if (p.compare() != null || p.compareFrom() != null) {
            throw Validate.invalid("Comparison is not available for this report");
        }
        Range range = range(ctx, p.from(), p.to());
        Map<String, List<BusinessSummaryService.Profitability>> data = business.projectProfitability(ctx.businessContext(), range.from().toString(), range.to().toString(), false);
        Map<String, List<Row>> rows = new TreeMap<>();
        for (BusinessSummaryService.Profitability pr : data.get("items")) {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("revenue_net", pr.revenueNet());
            v.put("costs", pr.costs());
            v.put("profit", pr.profit());
            v.put("margin_percent", pr.marginPercent() == null ? "" : pr.marginPercent());
            v.put("budget_used_percent", pr.budgetUsedPercent() == null ? "" : pr.budgetUsedPercent());
            rows.computeIfAbsent(pr.currency(), c -> new ArrayList<>()).add(new Row(pr.projectName(), range.from(), range.to(), true, v));
        }
        List<CurrencyReport> out = rows.entrySet().stream()
                .map(e -> new CurrencyReport(e.getKey(), List.of("revenue_net", "costs", "profit", "margin_percent", "budget_used_percent"), e.getValue(), Map.of())).toList();
        return new Report("project-profitability", "Project profitability", "BUSINESS", range.from(), range.to(), null, definitions("project-profitability"), notes("project-profitability"), out, null);
    }

    // ---------------------------------------------------------------- documentation of each metric (also returned by the API)

    static List<String> definitions(String name) {
        return switch (name) {
            case "cash-flow" -> List.of(
                    "income = POSTED INCOME transactions dated in the period (transaction date, user time zone)",
                    "expense = POSTED EXPENSE transactions; refunds = POSTED REFUND transactions; net_expense = expense - refunds",
                    "net = income - net_expense. Transfers, adjustments, drafts and voided transactions are excluded (a transfer is not income or expense)");
            case "business-performance" -> List.of(
                    "invoiced_net = sum of (line amounts - discounts) of ISSUED invoices by invoice date, excluding tax (accrual view)",
                    "received = payments that still stand (not voided) by payment date, including any unallocated part (cash view)",
                    "expenses = POSTED business expenses by expense date",
                    "operating_result = invoiced_net - expenses; net_cash = received - expenses",
                    "Drafts, cancelled invoices, voided payments and voided expenses are excluded");
            case "budget-vs-actual" -> List.of("budget = monthly limit; spent = net spending in the budget's category (including sub-categories) in the month, refunds deducted",
                    "percent_used = spent / budget; status UNDER below 80%, WARNING from 80% to 100%, OVER above 100%");
            case "net-worth" -> List.of("assets, liabilities and net_worth per currency at each month end (the current month ends today), from account balances");
            case "receivables" -> List.of("outstanding = invoice total - allocations of payments that still stand, for ISSUED invoices dated on or before the as-of date",
                    "overdue = the part whose due date is before the as-of date; buckets by days past due date");
            case "receivables-by-client" -> List.of("Same outstanding and overdue definitions as the receivables report, grouped by client (up to 500 rows)");
            default -> List.of("revenue_net = invoiced net revenue (excluding tax) of ISSUED invoices linked to the project dated in the period",
                    "costs = POSTED expenses linked to the project dated in the period; profit = revenue_net - costs",
                    "margin_percent = profit / revenue_net (blank when revenue is zero); budget_used_percent = costs / cost budget");
        };
    }

    static List<String> notes(String name) {
        List<String> notes = new ArrayList<>(List.of("Each currency is reported separately; nothing is converted.", "No tax, GST or accounting-compliance meaning is implied."));
        if ("business-performance".equals(name)) {
            notes.add("Revenue is shown when invoiced (accrual) and when received (cash) as separate columns; no revenue-recognition policy is applied.");
        }
        if ("budget-vs-actual".equals(name)) {
            notes.add("A parent category budget includes its sub-categories, so rows can overlap and are not totalled.");
        }
        return notes;
    }
}
