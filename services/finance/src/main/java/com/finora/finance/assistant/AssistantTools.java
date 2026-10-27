package com.finora.finance.assistant;

import com.finora.finance.assistant.AssistantModel.Built;
import com.finora.finance.assistant.AssistantModel.Period;
import com.finora.finance.assistant.AssistantModel.Section;
import com.finora.finance.assistant.AssistantModel.Source;
import com.finora.finance.assistant.AssistantModel.ToolResult;
import com.finora.finance.assistant.AssistantQueries.CategoryRow;
import com.finora.finance.assistant.AssistantQueries.Rec;
import com.finora.finance.assistant.ToolRegistry.ToolCall;
import com.finora.finance.core.Validate;
import com.finora.finance.insights.ForecastService;
import com.finora.finance.insights.InsightsContext;
import com.finora.finance.insights.ReportModel.CurrencyComparison;
import com.finora.finance.insights.ReportModel.CurrencyReport;
import com.finora.finance.insights.ReportModel.Report;
import com.finora.finance.insights.ReportService;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Executes one allowlisted, read-only tool. All numbers come from {@link ReportService}, {@link ForecastService} or the assistant's own exact
 * decimal arithmetic over the workspace's records; nothing is read from a model. Each tool says what it counted, over which dates and which
 * currencies; currencies are never mixed.
 */
@Component
class AssistantTools {

    private static final Map<String, String> LABELS = Map.ofEntries(Map.entry("income", "Income"), Map.entry("expense", "Expenses"), Map.entry("refunds", "Refunds"),
            Map.entry("net_expense", "Net spending (expenses minus refunds)"), Map.entry("net", "Net cash flow (income minus net spending)"),
            Map.entry("invoiced_net", "Invoiced, excluding tax (accrual)"), Map.entry("received", "Received (cash)"), Map.entry("expenses", "Expenses"),
            Map.entry("operating_result", "Operating result (invoiced minus expenses)"), Map.entry("net_cash", "Net cash (received minus expenses)"));
    private static final int MAX_LABEL = 60;

    private final ReportService reports;
    private final ForecastService forecasts;
    private final AssistantQueries queries;

    AssistantTools(ReportService reports, ForecastService forecasts, AssistantQueries queries) {
        this.reports = reports;
        this.forecasts = forecasts;
        this.queries = queries;
    }

    // ---------------------------------------------------------------- shared helpers

    record Range(LocalDate from, LocalDate to, boolean defaulted) {
    }

    private static ReportService.Params params(LocalDate from, LocalDate to, String compareFrom, String compareTo) {
        return new ReportService.Params(from.toString(), to.toString(), "month", null, compareFrom, compareTo, null, null);
    }

    /** from and to if both are given; otherwise the last complete month in the workspace time zone. */
    private Range range(InsightsContext ctx, ToolCall call) {
        LocalDate from = Validate.date("from", call.args().get("from"), false);
        LocalDate to = Validate.date("to", call.args().get("to"), false);
        if (from == null && to == null) {
            YearMonth last = YearMonth.from(ctx.today()).minusMonths(1);
            return new Range(last.atDay(1), last.atEndOfMonth(), true);
        }
        if (from == null || to == null) {
            throw Validate.invalid("Give both a start and an end date, or neither");
        }
        if (from.isAfter(to)) {
            throw Validate.invalid("The start date cannot be after the end date");
        }
        if (to.isAfter(ctx.today())) {
            throw Validate.invalid("The period cannot end in the future; the assistant only reads recorded data");
        }
        if (ChronoUnit.DAYS.between(from, to) > 3660) {
            throw Validate.invalid("The period can span at most 10 years");
        }
        return new Range(from, to, false);
    }

    private static String currency(ToolCall call) {
        String c = call.args().get("currency");
        if (c == null) {
            return null;
        }
        String up = c.toUpperCase(java.util.Locale.ROOT);
        if (!Currencies.supported(up)) {
            throw Validate.invalid("That currency is not supported; use one of " + Currencies.codes());
        }
        return up;
    }

    private static String label(String raw) {
        return Untrusted.clean(raw, MAX_LABEL);
    }

    private static Built plain(Section s) {
        return new Built(s, -1, true);
    }

    private static Built labelled(Section s, int column) {
        return new Built(s, column, true);
    }

    private static Built hidden(Section s) {
        return new Built(s, -1, false);
    }

    private static String label(String column, String fallback) {
        return LABELS.getOrDefault(column, fallback);
    }

    private static Period period(Range r, String label) {
        return new Period(r.from(), r.to(), label, null, null, null);
    }

    private static String windowLabel(Range r) {
        return r.from() + " to " + r.to() + " (" + (ChronoUnit.DAYS.between(r.from(), r.to()) + 1) + " days)";
    }

    private static List<String> defaultNote(Range r) {
        return r.defaulted() ? List.of("No period was given, so the last complete month was used.") : List.of();
    }

    private static Source report(String name, LocalDate from, LocalDate to) {
        return new Source("REPORT", name, from == null ? null : from.toString(), to == null ? null : to.toString(), null);
    }

    private static ToolResult noData(Period period, String what, List<Source> sources, List<String> assumptions) {
        return new ToolResult("NO_DATA", "There is no recorded " + what + " for " + (period == null ? "this workspace" : period.from() + " to " + period.to())
                + ", so there is nothing to report. Nothing was estimated.", null, period, List.of(), assumptions, List.of(), sources, 0);
    }

    private static int cmp(String a, String b) {
        return new BigDecimal(a).compareTo(new BigDecimal(b));
    }

    private static String percent(BigDecimal part, BigDecimal whole, int scale) {
        return whole.signum() == 0 ? null : part.multiply(BigDecimal.valueOf(100)).divide(whole.abs(), scale, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String pct(BigDecimal part, BigDecimal whole) {
        String p = percent(part, whole, 1);
        return p == null ? "n/a" : p + "%";
    }

    private static String signed(BigDecimal d, String cur) {
        String s = Currencies.format(d.setScale(Currencies.exponentOrThrow(cur), RoundingMode.HALF_EVEN), cur);
        return d.signum() > 0 ? "+" + s : s;
    }

    // ---------------------------------------------------------------- dispatch

    ToolResult run(InsightsContext ctx, ToolCall call) {
        ctx.requireReports();
        if (ToolRegistry.TOOLS.get(call.name()).fullDetail()) {
            ctx.requireFullReports();
        }
        return switch (call.name()) {
            case "period_summary" -> periodSummary(ctx, call);
            case "top_categories" -> topCategories(ctx, call);
            case "expense_change" -> expenseChange(ctx, call);
            case "cash_flow_trend" -> cashFlowTrend(ctx, call);
            case "anomalies" -> anomalies(ctx, call);
            case "forecast" -> forecast(ctx, call);
            case "what_if" -> whatIf(ctx, call);
            case "overdue_invoices" -> overdueInvoices(ctx);
            case "project_profitability" -> projectProfitability(ctx, call);
            default -> throw new ToolRegistry.Rejected("unknown tool");
        };
    }

    // ---------------------------------------------------------------- period_summary

    private ToolResult periodSummary(InsightsContext ctx, ToolCall call) {
        Range r = range(ctx, call);
        String only = currency(call);
        String name = ctx.business() ? "business-performance" : "cash-flow";
        Report rep = reports.report(ctx, name, params(r.from(), r.to(), null, null));
        List<Source> sources = List.of(report(name, r.from(), r.to()));
        Period p = period(r, windowLabel(r));
        List<Built> sections = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (CurrencyReport c : rep.currencies()) {
            if (only != null && !only.equals(c.currency())) {
                continue;
            }
            List<List<String>> rows = new ArrayList<>();
            for (String col : c.columns()) {
                rows.add(List.of(label(col, col), c.totals().get(col)));
            }
            sections.add(plain(new Section(rep.title() + " in " + c.currency(), "VERIFIED", c.currency(), List.of("Measure", "Amount"), rows, List.of())));
            if (ctx.business()) {
                lines.add(c.currency() + ": invoiced " + c.totals().get("invoiced_net") + ", received " + c.totals().get("received") + ", expenses " + c.totals().get("expenses") + ".");
            } else {
                lines.add(c.currency() + ": income " + c.totals().get("income") + ", net spending " + c.totals().get("net_expense") + " (expenses " + c.totals().get("expense")
                        + " minus refunds " + c.totals().get("refunds") + "), net cash flow " + c.totals().get("net") + ".");
            }
        }
        if (sections.isEmpty()) {
            return noData(p, "activity" + (only == null ? "" : " in " + only), sources, defaultNote(r));
        }
        List<String> assumptions = new ArrayList<>(defaultNote(r));
        assumptions.add("Figures are exact sums of recorded data in the workspace time zone, using the same definitions as the reports.");
        return new ToolResult("ANSWERED", "From " + r.from() + " to " + r.to() + ": " + String.join(" ", lines), null, p, sections, assumptions,
                List.of("Each currency is shown separately; nothing is converted.", "Only recorded data is included."), sources, 0);
    }

    // ---------------------------------------------------------------- top_categories

    private ToolResult topCategories(InsightsContext ctx, ToolCall call) {
        Range r = range(ctx, call);
        String only = currency(call);
        int limit = 5;
        if (call.args().containsKey("limit")) {
            try {
                limit = Integer.parseInt(call.args().get("limit"));
            } catch (NumberFormatException e) {
                throw Validate.invalid("limit must be a whole number from 1 to 10");
            }
            if (limit < 1 || limit > 10) {
                throw Validate.invalid("limit must be a whole number from 1 to 10");
            }
        }
        Period p = period(r, windowLabel(r));
        List<Source> sources = List.of(new Source("QUERY", ctx.business() ? "business-expenses-by-category" : "spending-by-category", r.from().toString(), r.to().toString(), null));
        Map<String, List<CategoryRow>> byCurrency = new TreeMap<>();
        long suspicious = 0;
        for (CategoryRow row : queries.categoryTotals(ctx, r.from(), r.to())) {
            String cur = row.currency().trim();
            if (only == null || only.equals(cur)) {
                byCurrency.computeIfAbsent(cur, k -> new ArrayList<>()).add(row);
                suspicious += Untrusted.suspicious(row.label()) ? 1 : 0;
            }
        }
        if (byCurrency.isEmpty()) {
            return noData(p, "spending" + (only == null ? "" : " in " + only), sources, defaultNote(r));
        }
        List<Built> sections = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (var e : byCurrency.entrySet()) {
            String cur = e.getKey();
            List<CategoryRow> rows = new ArrayList<>(e.getValue());
            rows.sort(Comparator.comparing(CategoryRow::amount).reversed().thenComparing(CategoryRow::label));
            BigDecimal total = rows.stream().map(CategoryRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<List<String>> out = new ArrayList<>();
            BigDecimal shown = BigDecimal.ZERO;
            for (CategoryRow row : rows.subList(0, Math.min(limit, rows.size()))) {
                shown = shown.add(row.amount());
                out.add(List.of(label(row.label()), Currencies.format(row.amount(), cur), pct(row.amount(), total), String.valueOf(row.records())));
            }
            if (rows.size() > limit) {
                BigDecimal rest = total.subtract(shown);
                long records = rows.subList(limit, rows.size()).stream().mapToLong(CategoryRow::records).sum();
                out.add(List.of("All other categories (" + (rows.size() - limit) + ")", Currencies.format(rest, cur), pct(rest, total), String.valueOf(records)));
            }
            sections.add(labelled(new Section("Net spending by category in " + cur + " (total " + Currencies.format(total, cur) + ")", "VERIFIED", cur,
                    List.of("Category", "Net amount", "Share of total", "Records"), out, List.of()), 0));
            CategoryRow top = rows.get(0);
            lines.add("In " + cur + " the largest category was " + label(top.label()) + " at " + Currencies.format(top.amount(), cur) + " out of " + Currencies.format(total, cur) + ".");
        }
        List<String> assumptions = new ArrayList<>(defaultNote(r));
        assumptions.add(ctx.business() ? "Posted business expenses by expense category; voided expenses are excluded."
                : "Spending is expenses minus refunds (a refund counts in its own category); transfers and voided transactions are excluded.");
        return new ToolResult("ANSWERED", "From " + r.from() + " to " + r.to() + ": " + String.join(" ", lines), null, p, sections, assumptions,
                List.of("Each currency is shown separately; nothing is converted.", "Uncategorised records are listed as Uncategorised."), sources, (int) suspicious);
    }

    // ---------------------------------------------------------------- expense_change

    /** The equivalent earlier window: the same whole months before, the same days of the previous month, or the same number of days before. */
    static Range[] windows(LocalDate from, LocalDate to) {
        YearMonth start = YearMonth.from(from);
        YearMonth end = YearMonth.from(to);
        boolean wholeMonths = from.getDayOfMonth() == 1 && to.equals(end.atEndOfMonth());
        if (wholeMonths) {
            long months = ChronoUnit.MONTHS.between(start, end) + 1;
            return new Range[] {new Range(from, to, false), new Range(from.minusMonths(months), from.minusDays(1), false)};
        }
        if (from.getDayOfMonth() == 1 && start.equals(end)) {
            YearMonth prev = start.minusMonths(1);
            LocalDate pto = prev.atDay(Math.min(to.getDayOfMonth(), prev.lengthOfMonth()));
            return new Range[] {new Range(from, to, false), new Range(prev.atDay(1), pto, false)};
        }
        long len = ChronoUnit.DAYS.between(from, to) + 1;
        return new Range[] {new Range(from, to, false), new Range(from.minusDays(len), from.minusDays(1), false)};
    }

    private ToolResult expenseChange(InsightsContext ctx, ToolCall call) {
        Range cur = range(ctx, call);
        String only = currency(call);
        Range[] w = windows(cur.from(), cur.to());
        Range prev = w[1];
        String name = ctx.business() ? "business-performance" : "cash-flow";
        String column = ctx.business() ? "expenses" : "net_expense";
        Report rep = reports.report(ctx, name, params(cur.from(), cur.to(), prev.from().toString(), prev.to().toString()));
        long curDays = ChronoUnit.DAYS.between(cur.from(), cur.to()) + 1;
        long prevDays = ChronoUnit.DAYS.between(prev.from(), prev.to()) + 1;
        Period p = new Period(cur.from(), cur.to(), windowLabel(cur), prev.from(), prev.to(), windowLabel(prev));
        List<Source> sources = new ArrayList<>(List.of(report(name, cur.from(), cur.to()), report(name, prev.from(), prev.to()),
                new Source("QUERY", ctx.business() ? "business-expenses-by-category" : "spending-by-category", cur.from().toString(), cur.to().toString(), null)));
        List<CurrencyComparison> comparisons = rep.comparison() == null ? List.of() : rep.comparison().currencies();
        if (comparisons.isEmpty()) {
            return noData(p, "spending in either period", sources, defaultNote(cur));
        }
        Map<String, Map<String, CategoryRow>> now = new TreeMap<>();
        Map<String, Map<String, CategoryRow>> before = new TreeMap<>();
        for (CategoryRow row : queries.categoryTotals(ctx, cur.from(), cur.to())) {
            now.computeIfAbsent(row.currency().trim(), k -> new LinkedHashMap<>()).put(row.key(), row);
        }
        for (CategoryRow row : queries.categoryTotals(ctx, prev.from(), prev.to())) {
            before.computeIfAbsent(row.currency().trim(), k -> new LinkedHashMap<>()).put(row.key(), row);
        }
        List<Built> sections = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<String> limitations = new ArrayList<>(List.of("This shows what changed in the recorded data, not why. A correlation in the records is not proof of a cause.",
                "Each currency is compared on its own; nothing is converted.", "No merchants, events or circumstances are inferred beyond what is recorded."));
        long suspicious = 0;
        for (CurrencyComparison cc : comparisons) {
            String c = cc.currency();
            if (only != null && !only.equals(c)) {
                continue;
            }
            BigDecimal a = new BigDecimal(cc.current().get(column));
            BigDecimal b = new BigDecimal(cc.previous().get(column));
            BigDecimal delta = a.subtract(b);
            String pct = percent(delta, b, 2);
            String direction = delta.signum() > 0 ? "rose" : delta.signum() < 0 ? "fell" : "did not change";
            List<List<String>> head = List.of(List.of(windowLabel(prev), Currencies.format(b, c)), List.of(windowLabel(cur), Currencies.format(a, c)),
                    List.of("Change", signed(delta, c) + (pct == null ? "" : " (" + pct + "%)")));
            List<String> notes = new ArrayList<>();
            if (b.signum() == 0) {
                notes.add("There was no recorded spending in the earlier period, so a percentage change cannot be computed.");
            }
            sections.add(plain(new Section((ctx.business() ? "Expenses" : "Net spending") + " in " + c + ": " + direction, "VERIFIED", c, List.of("Period", "Amount"), head, notes)));
            lines.add("In " + c + ", " + (ctx.business() ? "expenses " : "net spending ") + direction + (delta.signum() == 0 ? "" : " by " + Currencies.format(delta.abs(), c) + (pct == null ? "" : " (" + pct.replace("-", "") + "%)"))
                    + ": " + Currencies.format(b, c) + " before, " + Currencies.format(a, c) + " now.");
            // category drivers
            Map<String, CategoryRow> n = now.getOrDefault(c, Map.of());
            Map<String, CategoryRow> o = before.getOrDefault(c, Map.of());
            List<String> keys = new ArrayList<>(new TreeSet<>(java.util.stream.Stream.concat(n.keySet().stream(), o.keySet().stream()).toList()));
            record Driver(String label, BigDecimal now, BigDecimal before, BigDecimal delta, long nowRecords, long beforeRecords) {
            }
            List<Driver> drivers = new ArrayList<>();
            for (String k : keys) {
                CategoryRow x = n.get(k);
                CategoryRow y = o.get(k);
                BigDecimal xa = x == null ? BigDecimal.ZERO : x.amount();
                BigDecimal ya = y == null ? BigDecimal.ZERO : y.amount();
                drivers.add(new Driver(label((x != null ? x : y).label()), xa, ya, xa.subtract(ya), x == null ? 0 : x.records(), y == null ? 0 : y.records()));
                suspicious += Untrusted.suspicious((x != null ? x : y).label()) ? 1 : 0;
            }
            if (delta.signum() != 0) {
                int sign = delta.signum();
                drivers.sort(Comparator.comparing((Driver d) -> d.delta().multiply(BigDecimal.valueOf(sign))).reversed().thenComparing(Driver::label));
                List<List<String>> rows = new ArrayList<>();
                for (Driver d : drivers) {
                    if (rows.size() == 5 || d.delta().signum() != sign) {
                        break;
                    }
                    String share = percent(d.delta(), delta, 1);
                    rows.add(List.of(d.label(), Currencies.format(d.before(), c), Currencies.format(d.now(), c), signed(d.delta(), c), share == null ? "" : share + "%",
                            d.beforeRecords() + " / " + d.nowRecords() + (d.before().signum() == 0 ? " (new in this period)" : "")));
                }
                if (!rows.isEmpty()) {
                    sections.add(labelled(new Section("Categories that moved most in the direction of the change, " + c, "VERIFIED", c,
                            List.of("Category", "Earlier", "Now", "Change", "Share of total change", "Records (earlier / now)"), rows,
                            List.of("A category's share of the total change can exceed 100% when other categories moved the other way.")), 0));
                }
            }
            long recordsNow = n.values().stream().mapToLong(CategoryRow::records).sum();
            long recordsBefore = o.values().stream().mapToLong(CategoryRow::records).sum();
            if (recordsNow < 5 || recordsBefore < 5) {
                limitations.add("Small sample in " + c + ": " + recordsBefore + " earlier and " + recordsNow + " current record(s). A change this size can be ordinary variation.");
            }
        }
        if (sections.isEmpty()) {
            return noData(p, "spending" + (only == null ? "" : " in " + only), sources, defaultNote(cur));
        }
        List<String> assumptions = new ArrayList<>(defaultNote(cur));
        assumptions.add("Compared windows: " + windowLabel(cur) + " against " + windowLabel(prev) + ".");
        if (curDays != prevDays) {
            limitations.add("The windows have different lengths (" + curDays + " and " + prevDays + " days), so a part of the change can come from the number of days alone.");
        }
        // supporting records (largest in the current window) when this role may see individual records
        boolean detail = true;
        try {
            ctx.requireFullReports();
        } catch (RuntimeException e) {
            detail = false;
        }
        if (detail) {
            Map<String, List<Rec>> byCurrency = new TreeMap<>();
            for (Rec rec : queries.expenseRecords(ctx, cur.from(), cur.to())) {
                if (only == null || only.equals(rec.currency().trim())) {
                    byCurrency.computeIfAbsent(rec.currency().trim(), k -> new ArrayList<>()).add(rec);
                }
            }
            for (var e : byCurrency.entrySet()) {
                List<Rec> top = e.getValue().stream().sorted(Comparator.comparing(Rec::amount).reversed().thenComparing(Rec::id)).limit(3).toList();
                List<List<String>> rows = new ArrayList<>();
                for (Rec rec : top) {
                    suspicious += Untrusted.suspicious(rec.counterparty()) || Untrusted.suspicious(rec.category()) ? 1 : 0;
                    rows.add(List.of(rec.onDate().toString(), Currencies.format(rec.amount(), e.getKey()), label(rec.category()), label(rec.counterparty()), "record:" + rec.id()));
                    sources.add(new Source(ctx.business() ? "EXPENSE" : "TRANSACTION", "record", null, null, rec.id().toString()));
                }
                sections.add(hidden(new Section("Largest individual expense records in the current period, " + e.getKey(), "VERIFIED", e.getKey(),
                        List.of("Date", "Amount", "Category", "Payee / vendor", "Reference"), rows, List.of("Payee and vendor names are shown as recorded and are not sent to any AI provider."))));
            }
        }
        return new ToolResult("ANSWERED", String.join(" ", lines), null, p, sections, assumptions, limitations, sources, (int) suspicious);
    }

    // ---------------------------------------------------------------- cash_flow_trend

    private ToolResult cashFlowTrend(InsightsContext ctx, ToolCall call) {
        int months = 3;
        if (call.args().containsKey("months")) {
            try {
                months = Integer.parseInt(call.args().get("months"));
            } catch (NumberFormatException e) {
                throw Validate.invalid("months must be a whole number from 2 to 12");
            }
        }
        if (months < 2 || months > 12) {
            throw Validate.invalid("months must be a whole number from 2 to 12");
        }
        String only = currency(call);
        YearMonth last = YearMonth.from(ctx.today()).minusMonths(1);
        LocalDate from = last.minusMonths(months - 1L).atDay(1);
        LocalDate to = last.atEndOfMonth();
        String name = ctx.business() ? "business-performance" : "cash-flow";
        String column = ctx.business() ? "net_cash" : "net";
        Report rep = reports.report(ctx, name, params(from, to, null, null));
        Period p = new Period(from, to, "the last " + months + " complete months (" + from + " to " + to + ")", null, null, null);
        List<Source> sources = List.of(report(name, from, to));
        List<Built> sections = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<String> limitations = new ArrayList<>(List.of("Each currency is shown separately; nothing is converted.", "The current, incomplete month is not included."));
        for (CurrencyReport c : rep.currencies()) {
            if (only != null && !only.equals(c.currency())) {
                continue;
            }
            List<List<String>> rows = new ArrayList<>();
            List<String> cols = ctx.business() ? List.of("received", "expenses", "net_cash") : List.of("income", "net_expense", "net");
            int withActivity = 0;
            for (var row : c.rows()) {
                List<String> cells = new ArrayList<>(List.of(row.period()));
                cols.forEach(k -> cells.add(row.values().get(k)));
                cells.add(row.activity() ? "yes" : "no activity");
                withActivity += row.activity() ? 1 : 0;
                rows.add(cells);
            }
            List<String> head = new ArrayList<>(List.of("Month"));
            cols.forEach(k -> head.add(label(k, k)));
            head.add("Recorded activity");
            sections.add(plain(new Section("Monthly " + (ctx.business() ? "net cash" : "net cash flow") + " in " + c.currency(), "VERIFIED", c.currency(), head, rows, List.of())));
            if (withActivity < 2) {
                limitations.add("Only " + withActivity + " month(s) had activity in " + c.currency() + ", so no change between months is stated.");
                continue;
            }
            var firstRow = c.rows().get(0);
            var lastRow = c.rows().get(c.rows().size() - 1);
            BigDecimal a = new BigDecimal(firstRow.values().get(column));
            BigDecimal b = new BigDecimal(lastRow.values().get(column));
            BigDecimal delta = b.subtract(a);
            String pct = percent(delta, a, 2);
            lines.add("In " + c.currency() + ", " + (ctx.business() ? "net cash" : "net cash flow") + " went from " + Currencies.format(a, c.currency()) + " in " + firstRow.period() + " to "
                    + Currencies.format(b, c.currency()) + " in " + lastRow.period() + " (change " + signed(delta, c.currency()) + (pct == null ? "" : ", " + pct + "%") + ").");
            if (!firstRow.activity() || !lastRow.activity()) {
                limitations.add("In " + c.currency() + " the first or last month had no recorded activity, so that month shows zero rather than a real result.");
            }
        }
        if (sections.isEmpty()) {
            return noData(p, "activity" + (only == null ? "" : " in " + only), sources, List.of());
        }
        return new ToolResult("ANSWERED", lines.isEmpty() ? "There is not enough recorded activity to state a change over the last " + months + " months." : String.join(" ", lines), null, p,
                sections, List.of("The change compares the first and the last of the " + months + " complete months."), limitations, sources, 0);
    }

    // ---------------------------------------------------------------- anomalies

    private ToolResult anomalies(InsightsContext ctx, ToolCall call) {
        Range r = range(ctx, call);
        String only = currency(call);
        Period p = period(r, windowLabel(r));
        long len = ChronoUnit.DAYS.between(r.from(), r.to()) + 1;
        LocalDate baseFrom = r.from().minusDays(Math.max(183, len * 3));
        List<Source> sources = List.of(new Source("QUERY", ctx.business() ? "expense-records" : "expense-transactions", baseFrom.toString(), r.to().toString(), null));
        List<Rec> all = queries.expenseRecords(ctx, baseFrom, r.to()).stream().filter(x -> only == null || only.equals(x.currency().trim())).toList();
        List<Rec> window = all.stream().filter(x -> !x.onDate().isBefore(r.from())).toList();
        List<Rec> baseline = all.stream().filter(x -> x.onDate().isBefore(r.from())).toList();
        List<String> limitations = new ArrayList<>(List.of(
                "These are potential anomalies from fixed rules, not findings of error or fraud; one-off purchases, seasonality and new habits look like this too.",
                "LARGE_AMOUNT needs at least " + AnomalyDetector.MIN_BASELINE_RECORDS + " earlier records in the same category and currency (up to 6 months back).",
                "CATEGORY_SPIKE needs a period of at least " + AnomalyDetector.MIN_PERIOD_DAYS + " days and spending in the same category in each of the 3 previous equal periods.",
                "POSSIBLE_DUPLICATE compares payee or vendor, amount and currency within " + AnomalyDetector.DUPLICATE_DAYS + " days; records without a payee or vendor are not checked.",
                "Only expense records are examined; income, transfers and refunds are not."));
        if (all.size() >= AssistantQueries.MAX_RECORDS) {
            limitations.add("The record limit of " + AssistantQueries.MAX_RECORDS + " was reached, so the oldest baseline records were left out.");
        }
        if (window.isEmpty()) {
            return noData(p, "expenses" + (only == null ? "" : " in " + only), sources, defaultNote(r));
        }
        List<AnomalyDetector.Finding> found = AnomalyDetector.detect(window, baseline, r.from(), r.to());
        List<AnomalyDetector.Finding> shown = found.size() > AnomalyDetector.MAX_FINDINGS ? found.subList(0, AnomalyDetector.MAX_FINDINGS) : found;
        if (found.size() > shown.size()) {
            limitations.add(found.size() + " potential anomalies were found; the " + AnomalyDetector.MAX_FINDINGS + " most recent are shown.");
        }
        List<String> assumptions = new ArrayList<>(defaultNote(r));
        assumptions.add("Baseline: the records before " + r.from() + " (about 6 months, or three periods of the same length if longer).");
        long suspicious = 0;
        List<Built> sections = new ArrayList<>();
        List<Source> allSources = new ArrayList<>(sources);
        if (shown.isEmpty()) {
            return new ToolResult("ANSWERED", "No potential anomalies were found between " + r.from() + " and " + r.to() + " by the documented rules (" + window.size()
                    + " expense record(s) examined). That does not mean nothing unusual happened: the rules need enough earlier records to compare against.", null, p, List.of(),
                    assumptions, limitations, allSources, 0);
        }
        List<List<String>> rows = new ArrayList<>();
        for (AnomalyDetector.Finding f : shown) {
            suspicious += Untrusted.suspicious(f.counterparty()) || Untrusted.suspicious(f.category()) ? 1 : 0;
            String ref = f.recordId() == null ? "category total" : "record:" + f.recordId();
            rows.add(List.of(f.rule(), f.date() == null ? "period" : f.date().toString(), f.amount() + " " + f.currency(), label(f.category()), label(f.counterparty()), f.reason(), ref));
            if (f.recordId() != null) {
                allSources.add(new Source(ctx.business() ? "EXPENSE" : "TRANSACTION", "record", null, null, f.recordId().toString()));
            }
        }
        sections.add(hidden(new Section("Potential anomalies", "OBSERVATION", null, List.of("Rule", "Date", "Amount", "Category", "Payee / vendor", "Why it was flagged", "Reference"), rows,
                List.of("Potential anomaly: for you to review. Nothing was changed, blocked or reported."))));
        return new ToolResult("ANSWERED", shown.size() + " potential anomal" + (shown.size() == 1 ? "y" : "ies") + " found between " + r.from() + " and " + r.to() + " among " + window.size()
                + " expense record(s). Each one lists the rule and figures behind it.", null, p, sections, assumptions, limitations, allSources, (int) suspicious);
    }

    /** Used by the structured endpoint and the tests: the raw findings for a window. */
    List<AnomalyDetector.Finding> findings(InsightsContext ctx, LocalDate from, LocalDate to) {
        long len = ChronoUnit.DAYS.between(from, to) + 1;
        List<Rec> all = queries.expenseRecords(ctx, from.minusDays(Math.max(183, len * 3)), to);
        return AnomalyDetector.detect(all.stream().filter(x -> !x.onDate().isBefore(from)).toList(), all.stream().filter(x -> x.onDate().isBefore(from)).toList(), from, to);
    }

    // ---------------------------------------------------------------- forecast

    private static Integer integer(ToolCall call, String key) {
        String v = call.args().get(key);
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            throw Validate.invalid(key + " must be a whole number");
        }
    }

    private ToolResult forecast(InsightsContext ctx, ToolCall call) {
        ForecastService.Forecast f = forecasts.forecast(ctx, call.args().get("metric"), integer(call, "lookback_months"), integer(call, "horizon_months"));
        Period p = new Period(f.historyFrom(), f.historyTo(), "history " + f.historyFrom() + " to " + f.historyTo() + " (" + f.lookbackMonths() + " complete months)", null, null, null);
        List<Source> sources = List.of(new Source("FORECAST", f.metric(), f.historyFrom().toString(), f.historyTo().toString(), null));
        List<Built> sections = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<String> limitations = new ArrayList<>(List.of("This is an estimate, not an actual result and not a guarantee.", "The range is an indicative spread, not a statistical confidence interval."));
        for (ForecastService.CurrencyForecast c : f.currencies()) {
            if (!"ESTIMATE".equals(c.status())) {
                limitations.add(c.message());
                continue;
            }
            List<List<String>> rows = new ArrayList<>();
            for (ForecastService.Projection pr : c.projection()) {
                rows.add(List.of(pr.month(), pr.estimate(), pr.low(), pr.high()));
            }
            sections.add(plain(new Section("Estimate of " + f.metric() + " per month in " + c.currency() + " (confidence: " + c.confidence() + ")", "ESTIMATE", c.currency(),
                    List.of("Month", "Estimate", "Low", "High"), rows, c.warnings())));
            lines.add("In " + c.currency() + " the baseline estimate of " + f.metric() + " is " + c.estimatePerMonth() + " per month (indicative range " + c.low() + " to " + c.high()
                    + ", confidence " + c.confidence() + ").");
        }
        if (sections.isEmpty()) {
            return new ToolResult("NO_DATA", f.currencies().isEmpty() ? "There is no recorded history to base an estimate on. Nothing was estimated."
                    : "There is not enough recorded history in any currency to show an estimate. Nothing was estimated.", null, p, List.of(), f.assumptions(), limitations, sources, 0);
        }
        return new ToolResult("ANSWERED", String.join(" ", lines), null, p, sections, f.assumptions(), limitations, sources, 0);
    }

    // ---------------------------------------------------------------- what_if

    private static BigDecimal assumption(ToolCall call, String key) {
        String v = call.args().get(key);
        if (v == null) {
            return null;
        }
        BigDecimal d;
        try {
            d = new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw Validate.invalid(key + " must be a number such as 10 or -20");
        }
        if (d.scale() > 2 || d.compareTo(BigDecimal.valueOf(-100)) < 0 || d.compareTo(BigDecimal.valueOf(1000)) > 0) {
            throw Validate.invalid(key + " must be between -100 and 1000 with at most 2 decimals");
        }
        return d;
    }

    private ToolResult whatIf(InsightsContext ctx, ToolCall call) {
        BigDecimal expense = assumption(call, "expense_change_percent");
        BigDecimal revenue = assumption(call, "revenue_change_percent");
        if (expense == null && revenue == null) {
            return new ToolResult("NEEDS_CLARIFICATION", null, "Tell me at least one assumption, for example \"expenses increase by 10%\" or \"revenue drops by 20%\".", null, List.of(), List.of(), List.of(),
                    List.of(), 0);
        }
        BigDecimal e = expense == null ? BigDecimal.ZERO : expense;
        BigDecimal v = revenue == null ? BigDecimal.ZERO : revenue;
        Integer lookback = integer(call, "lookback_months");
        Integer horizon = integer(call, "horizon_months");
        String revenueMetric = ctx.business() ? "received" : "income";
        String expenseMetric = ctx.business() ? "expenses" : "net_expense";
        ForecastService.Forecast rev = forecasts.forecast(ctx, revenueMetric, lookback, horizon);
        ForecastService.Forecast exp = forecasts.forecast(ctx, expenseMetric, lookback, horizon);
        Period p = new Period(rev.historyFrom(), rev.historyTo(), "baseline history " + rev.historyFrom() + " to " + rev.historyTo(), null, null, null);
        List<Source> sources = List.of(new Source("FORECAST", revenueMetric, rev.historyFrom().toString(), rev.historyTo().toString(), null),
                new Source("FORECAST", expenseMetric, exp.historyFrom().toString(), exp.historyTo().toString(), null));
        Map<String, ForecastService.CurrencyForecast> expBy = new TreeMap<>();
        exp.currencies().forEach(c -> expBy.put(c.currency(), c));
        List<Built> sections = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<String> limitations = new ArrayList<>(List.of("Hypothetical: this is arithmetic on your assumptions, not a prediction. It is not saved and changes no budget, invoice, payment or record.",
                "The baseline is the Step 19 flat estimate (no trend or seasonality), so the scenario inherits all of its limits."));
        BigDecimal revFactor = BigDecimal.ONE.add(v.divide(BigDecimal.valueOf(100), 6, RoundingMode.UNNECESSARY));
        BigDecimal expFactor = BigDecimal.ONE.add(e.divide(BigDecimal.valueOf(100), 6, RoundingMode.UNNECESSARY));
        for (ForecastService.CurrencyForecast rc : rev.currencies()) {
            ForecastService.CurrencyForecast ec = expBy.get(rc.currency());
            String cur = rc.currency();
            if (!"ESTIMATE".equals(rc.status()) || ec == null || !"ESTIMATE".equals(ec.status())) {
                limitations.add("No scenario for " + cur + ": it needs a baseline estimate for both " + revenueMetric + " and " + expenseMetric + " (at least " + ForecastService.MIN_MONTHS + " months of activity each).");
                continue;
            }
            int exponent = Currencies.exponentOrThrow(cur);
            List<List<String>> rows = new ArrayList<>();
            BigDecimal netBase = BigDecimal.ZERO;
            BigDecimal netScenario = BigDecimal.ZERO;
            for (int i = 0; i < rc.projection().size(); i++) {
                BigDecimal rb = new BigDecimal(rc.projection().get(i).estimate());
                BigDecimal eb = new BigDecimal(ec.projection().get(i).estimate());
                BigDecimal rs = rb.multiply(revFactor).setScale(exponent, RoundingMode.HALF_EVEN);
                BigDecimal es = eb.multiply(expFactor).setScale(exponent, RoundingMode.HALF_EVEN);
                BigDecimal nb = rb.subtract(eb);
                BigDecimal ns = rs.subtract(es);
                netBase = netBase.add(nb);
                netScenario = netScenario.add(ns);
                rows.add(List.of(rc.projection().get(i).month(), Currencies.format(rb, cur), Currencies.format(rs, cur), Currencies.format(eb, cur), Currencies.format(es, cur),
                        Currencies.format(nb, cur), Currencies.format(ns, cur), signed(ns.subtract(nb), cur)));
            }
            sections.add(plain(new Section("Hypothetical scenario in " + cur + " (" + revenueMetric + " " + (v.signum() >= 0 ? "+" : "") + v.toPlainString() + "%, " + expenseMetric + " "
                    + (e.signum() >= 0 ? "+" : "") + e.toPlainString() + "%)", "HYPOTHETICAL", cur,
                    List.of("Month", "Baseline " + revenueMetric, "Scenario " + revenueMetric, "Baseline " + expenseMetric, "Scenario " + expenseMetric, "Baseline net", "Scenario net", "Net change"),
                    rows, List.of("Net = " + revenueMetric + " minus " + expenseMetric + ". Over the horizon the baseline net is " + Currencies.format(netBase, cur) + " and the scenario net is "
                            + Currencies.format(netScenario, cur) + "."))));
            lines.add("In " + cur + ", over " + rc.projection().size() + " month(s) your assumptions would change the estimated net from " + Currencies.format(netBase, cur) + " to "
                    + Currencies.format(netScenario, cur) + " (" + signed(netScenario.subtract(netBase), cur) + ").");
        }
        if (sections.isEmpty()) {
            return new ToolResult("NO_DATA", "There is not enough recorded history to build a baseline, so no scenario was computed. Nothing was estimated.", null, p, List.of(),
                    List.of("Assumptions you gave: " + revenueMetric + " " + v.toPlainString() + "%, " + expenseMetric + " " + e.toPlainString() + "%."), limitations, sources, 0);
        }
        List<String> assumptions = new ArrayList<>(List.of("Your assumptions: " + revenueMetric + " changes by " + v.toPlainString() + "% and " + expenseMetric + " changes by " + e.toPlainString() + "%, applied to every month."));
        assumptions.addAll(rev.assumptions());
        return new ToolResult("ANSWERED", "Hypothetical scenario (not a prediction, not saved): " + String.join(" ", lines), null, p, sections, assumptions, limitations, sources, 0);
    }

    // ---------------------------------------------------------------- business-only tools

    private ToolResult overdueInvoices(InsightsContext ctx) {
        LocalDate today = ctx.today();
        Report agg = reports.report(ctx, "receivables", new ReportService.Params(null, null, null, null, null, null, null, null));
        Report byClient = reports.report(ctx, "receivables-by-client", new ReportService.Params(null, null, null, null, null, null, null, null));
        List<Source> sources = List.of(report("receivables", null, today), report("receivables-by-client", null, today));
        Period p = new Period(null, today, "as of " + today, null, null, null);
        if (agg.currencies().isEmpty()) {
            return new ToolResult("NO_DATA", "There are no outstanding issued invoices as of " + today + ", so there is nothing overdue. Nothing was estimated.", null, p, List.of(),
                    List.of("Outstanding means issued invoices not fully paid by recorded payments."), List.of(), sources, 0);
        }
        List<Built> sections = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        long suspicious = 0;
        Map<String, CurrencyReport> clients = new TreeMap<>();
        byClient.currencies().forEach(c -> clients.put(c.currency(), c));
        for (CurrencyReport c : agg.currencies()) {
            String overdue = c.totals().get("overdue");
            lines.add(c.currency() + ": " + overdue + " overdue out of " + c.totals().get("outstanding") + " outstanding across " + c.totals().get("invoices") + " invoice(s).");
            List<List<String>> rows = new ArrayList<>();
            c.rows().forEach(r -> rows.add(List.of(r.period(), r.values().get("amount"))));
            sections.add(plain(new Section("Receivables aging in " + c.currency() + " as of " + today, "VERIFIED", c.currency(), List.of("Bucket", "Amount"), rows, List.of())));
            CurrencyReport cc = clients.get(c.currency());
            if (cc != null && cmp(overdue, "0") > 0) {
                List<List<String>> top = new ArrayList<>();
                for (var r : cc.rows().stream().filter(x -> cmp(x.values().get("overdue"), "0") > 0)
                        .sorted((x, y) -> cmp(y.values().get("overdue"), x.values().get("overdue"))).limit(5).toList()) {
                    suspicious += Untrusted.suspicious(r.period()) ? 1 : 0;
                    top.add(List.of(label(r.period()), r.values().get("overdue"), r.values().get("outstanding"), r.values().get("invoices")));
                }
                sections.add(labelled(new Section("Clients with the most overdue, " + c.currency(), "VERIFIED", c.currency(), List.of("Client", "Overdue", "Outstanding", "Invoices"), top, List.of()), 0));
            }
        }
        return new ToolResult("ANSWERED", "As of " + today + ": " + String.join(" ", lines), null, p, sections,
                List.of("Overdue = the unpaid part of issued invoices whose due date is before " + today + ", after payments that still stand."), List.of("Each currency is shown separately; nothing is converted."),
                sources, (int) suspicious);
    }

    private ToolResult projectProfitability(InsightsContext ctx, ToolCall call) {
        Range r = range(ctx, call);
        String order = call.args().getOrDefault("order", "lowest");
        if (!order.equals("lowest") && !order.equals("highest")) {
            throw Validate.invalid("order must be lowest or highest");
        }
        int limit = 5;
        if (call.args().containsKey("limit")) {
            try {
                limit = Integer.parseInt(call.args().get("limit"));
            } catch (NumberFormatException e) {
                throw Validate.invalid("limit must be a whole number from 1 to 10");
            }
            if (limit < 1 || limit > 10) {
                throw Validate.invalid("limit must be a whole number from 1 to 10");
            }
        }
        Report rep = reports.report(ctx, "project-profitability", params(r.from(), r.to(), null, null));
        Period p = period(r, windowLabel(r));
        List<Source> sources = List.of(report("project-profitability", r.from(), r.to()));
        if (rep.currencies().isEmpty()) {
            return noData(p, "project revenue or costs", sources, defaultNote(r));
        }
        List<Built> sections = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        long suspicious = 0;
        for (CurrencyReport c : rep.currencies()) {
            List<ReportRowView> rows = c.rows().stream().filter(x -> cmp(x.values().get("revenue_net"), "0") != 0 || cmp(x.values().get("costs"), "0") != 0)
                    .map(x -> new ReportRowView(x.period(), x.values())).toList();
            if (rows.isEmpty()) {
                continue;
            }
            Comparator<ReportRowView> byProfit = (x, y) -> cmp(x.values().get("profit"), y.values().get("profit"));
            List<ReportRowView> sorted = rows.stream().sorted(order.equals("lowest") ? byProfit : byProfit.reversed()).limit(limit).toList();
            List<List<String>> out = new ArrayList<>();
            for (ReportRowView x : sorted) {
                suspicious += Untrusted.suspicious(x.name()) ? 1 : 0;
                out.add(List.of(label(x.name()), x.values().get("revenue_net"), x.values().get("costs"), x.values().get("profit"),
                        x.values().get("margin_percent").isEmpty() ? "n/a (no revenue)" : x.values().get("margin_percent") + "%"));
            }
            sections.add(labelled(new Section("Projects with the " + order + " profit in " + c.currency(), "VERIFIED", c.currency(),
                    List.of("Project", "Revenue (net)", "Costs", "Profit", "Margin"), out, List.of()), 0));
            lines.add("In " + c.currency() + " the project with the " + order + " profit is " + label(sorted.get(0).name()) + " at " + sorted.get(0).values().get("profit") + ".");
        }
        if (sections.isEmpty()) {
            return noData(p, "project revenue or costs", sources, defaultNote(r));
        }
        return new ToolResult("ANSWERED", "From " + r.from() + " to " + r.to() + ": " + String.join(" ", lines), null, p, sections,
                List.of("Profit = net invoiced revenue minus posted expenses linked to the project, dated within the period.", "Costs not linked to a project are not included in any project."),
                List.of("Each currency is shown separately; nothing is converted.", "Only projects with revenue or costs in the period appear."), sources, (int) suspicious);
    }

    private record ReportRowView(String name, Map<String, String> values) {
    }
}
