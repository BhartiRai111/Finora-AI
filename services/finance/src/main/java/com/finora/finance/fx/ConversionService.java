package com.finora.finance.fx;

import com.finora.finance.core.Validate;
import com.finora.finance.fx.RateService.Lookup;
import com.finora.finance.insights.Csv;
import com.finora.finance.insights.InsightsContext;
import com.finora.finance.insights.ReportModel.CurrencyReport;
import com.finora.finance.insights.ReportModel.Report;
import com.finora.finance.insights.ReportModel.Row;
import com.finora.finance.insights.ReportService;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * Presents an existing report in a reporting currency WITHOUT touching the report itself. The original per-currency report is returned unchanged
 * beside a separate conversion block. Each original amount is converted exactly once from its own stored value (never from an earlier
 * conversion), at the rate in force on the end date of its period, rounded half-even to the reporting currency's minor unit. If any period of a
 * currency has no usable rate, that currency is shown as UNAVAILABLE and contributes to no combined total. Rate value, date, age and source
 * are listed for every period, so every converted figure can be traced.
 */
@Service
public class ConversionService {

    public record RateRef(String status, String rate, LocalDate rateDate, Long ageDays, String source, String message) {
    }

    public record ConvertedRow(String period, LocalDate rateAsOf, RateRef rate, Map<String, String> values) {
    }

    /** status: SAME, CONVERTED, CONVERTED_WITH_STALE_RATES, UNAVAILABLE. */
    public record CurrencyConversion(String currency, String status, String message, List<ConvertedRow> rows, Map<String, String> totals, RateRef totalsRate) {
    }

    public record Conversion(String reportingCurrency, boolean allowStale, String method, List<CurrencyConversion> currencies, Map<String, String> combinedTotals,
            String combinedUnavailableReason, List<String> notes) {
    }

    public record Converted(Report report, Conversion conversion) {
    }

    private static final Map<String, List<String>> ROW_MONEY = Map.of(
            "cash-flow", List.of("income", "expense", "refunds", "net_expense", "net"),
            "business-performance", List.of("invoiced_net", "received", "expenses", "operating_result", "net_cash"),
            "budget-vs-actual", List.of("budget", "spent", "remaining"),
            "net-worth", List.of("assets", "liabilities", "net_worth"),
            "receivables", List.of("amount"),
            "receivables-by-client", List.of("outstanding", "overdue"),
            "project-profitability", List.of("revenue_net", "costs", "profit"));
    private static final Map<String, List<String>> TOTAL_MONEY = Map.of(
            "cash-flow", ROW_MONEY.get("cash-flow"), "business-performance", ROW_MONEY.get("business-performance"),
            "receivables", List.of("outstanding", "overdue"), "receivables-by-client", List.of("outstanding", "overdue"));
    /** Series whose totals are plain sums of their periods: the converted total is the sum of the converted periods. */
    private static final Set<String> ADDITIVE = Set.of("cash-flow", "business-performance");

    private final ReportService reports;
    private final RateService rates;

    public ConversionService(ReportService reports, RateService rates) {
        this.reports = reports;
        this.rates = rates;
    }

    private static String target(String raw) {
        if (raw == null || raw.isBlank()) {
            throw Validate.invalid("reporting_currency is required");
        }
        String c = raw.trim().toUpperCase(Locale.ROOT);
        if (!Currencies.supported(c)) {
            throw Validate.invalid("reporting_currency is not a supported currency");
        }
        return c;
    }

    private static RateRef ref(Lookup l) {
        return new RateRef(l.status(), l.rate() == null ? null : l.rate().stripTrailingZeros().toPlainString(), l.rateDate(), l.ageDays(), l.source(), l.message());
    }

    public Converted convert(InsightsContext ctx, String name, ReportService.Params params, String reportingCurrency, boolean allowStale) {
        ctx.requireReports();
        String to = target(reportingCurrency);
        Report report = reports.report(ctx, name, params);
        List<String> rowCols = ROW_MONEY.getOrDefault(name, List.of());
        List<String> totalCols = TOTAL_MONEY.getOrDefault(name, List.of());
        boolean additive = ADDITIVE.contains(name);
        LocalDate fallback = report.to() != null ? report.to() : ctx.today();
        Map<String, Lookup> cache = new HashMap<>();
        List<CurrencyConversion> out = new ArrayList<>();
        for (CurrencyReport c : report.currencies()) {
            String from = c.currency();
            List<ConvertedRow> rows = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            boolean stale = false;
            Map<String, BigDecimal> sums = new LinkedHashMap<>();
            for (Row r : c.rows()) {
                LocalDate asOf = clip(r.end() != null ? r.end() : fallback, ctx);
                Lookup l = cache.computeIfAbsent(from + ">" + to + "@" + asOf, k -> rates.find(ctx, from, to, asOf));
                if (!l.usable(allowStale)) {
                    problems.add(r.period() + ": " + l.message());
                    continue;
                }
                stale |= "STALE".equals(l.status());
                Map<String, String> values = new LinkedHashMap<>();
                for (String col : rowCols) {
                    String v = r.values().get(col);
                    if (v == null || v.isBlank()) {
                        continue;
                    }
                    BigDecimal converted = "SAME".equals(l.status()) ? new BigDecimal(v) : RateService.convert(new BigDecimal(v), l.rate(), to);
                    values.put(col, Currencies.format(converted.setScale(Currencies.exponentOrThrow(to), java.math.RoundingMode.HALF_EVEN), to));
                    sums.merge(col, converted, BigDecimal::add);
                }
                rows.add(new ConvertedRow(r.period(), asOf, ref(l), values));
            }
            if (!problems.isEmpty()) {
                out.add(new CurrencyConversion(from, "UNAVAILABLE", "Not converted: " + String.join("; ", problems.subList(0, Math.min(3, problems.size())))
                        + (problems.size() > 3 ? "; and " + (problems.size() - 3) + " more period(s)" : "") + ". The original " + from + " figures above are unaffected.", List.of(), Map.of(), null));
                continue;
            }
            Map<String, String> totals = new LinkedHashMap<>();
            RateRef totalsRate = null;
            if (additive) {
                for (String col : totalCols) {
                    totals.put(col, Currencies.format(sums.getOrDefault(col, BigDecimal.ZERO).setScale(Currencies.exponentOrThrow(to), java.math.RoundingMode.HALF_EVEN), to));
                }
            } else if (!c.totals().isEmpty() && !totalCols.isEmpty()) {
                LocalDate asOf = clip(fallback, ctx);
                Lookup l = cache.computeIfAbsent(from + ">" + to + "@" + asOf, k -> rates.find(ctx, from, to, asOf));
                if (!l.usable(allowStale)) {
                    out.add(new CurrencyConversion(from, "UNAVAILABLE", "Totals not converted: " + l.message() + ". The original " + from + " figures above are unaffected.", List.of(), Map.of(), null));
                    continue;
                }
                stale |= "STALE".equals(l.status());
                totalsRate = ref(l);
                for (String col : totalCols) {
                    String v = c.totals().get(col);
                    if (v != null && !v.isBlank()) {
                        BigDecimal converted = "SAME".equals(l.status()) ? new BigDecimal(v) : RateService.convert(new BigDecimal(v), l.rate(), to);
                        totals.put(col, Currencies.format(converted.setScale(Currencies.exponentOrThrow(to), java.math.RoundingMode.HALF_EVEN), to));
                    }
                }
            }
            String status = from.equals(to) ? "SAME" : stale ? "CONVERTED_WITH_STALE_RATES" : "CONVERTED";
            out.add(new CurrencyConversion(from, status, stale ? "Includes stale rate(s) you chose to accept; see each period." : null, rows, totals, totalsRate));
        }
        Map<String, String> combined = null;
        String reason = null;
        if (out.isEmpty()) {
            reason = "There is nothing to combine.";
        } else if (out.stream().anyMatch(c -> "UNAVAILABLE".equals(c.status()))) {
            reason = "At least one currency could not be converted, so no combined total is shown. Currencies are never added together without a valid rate.";
        } else if (out.stream().allMatch(c -> c.totals().isEmpty())) {
            reason = "This report has no totals to combine.";
        } else {
            Map<String, BigDecimal> sum = new LinkedHashMap<>();
            for (CurrencyConversion c : out) {
                c.totals().forEach((k, v) -> sum.merge(k, new BigDecimal(v), BigDecimal::add));
            }
            combined = new LinkedHashMap<>();
            for (var e : sum.entrySet()) {
                combined.put(e.getKey(), Currencies.format(e.getValue(), to));
            }
        }
        List<String> notes = new ArrayList<>(List.of(
                "Original amounts are shown unchanged in the report. Converted values are derived, never stored, and are computed from each original amount once.",
                "Each period is converted at the latest rate dated on or before the period's last day (a later rate is never used). Rates are entered by a person (USER_SUPPLIED); no market rate source is configured.",
                additive ? "Converted totals are the sum of the converted periods, so they can differ by rounding from converting the original total once."
                        : "Converted totals are the original totals converted once at the rate for the report's end date."));
        return new Converted(report, new Conversion(to, allowStale, "Per-period conversion at the rate in force on the period's last day, rounded half-even to " + to + "'s minor unit",
                out, combined, reason, notes));
    }

    private static LocalDate clip(LocalDate d, InsightsContext ctx) {
        return d.isAfter(ctx.today()) ? ctx.today() : d;
    }

    /** The original CSV followed by a clearly separate converted block; every cell goes through the same formula protection. */
    public String exportCsv(InsightsContext ctx, String name, ReportService.Params params, String reportingCurrency, boolean allowStale) {
        String original = reports.exportCsv(ctx, name, params);
        Converted c = convert(ctx, name, params, reportingCurrency, allowStale);
        Set<String> cols = new LinkedHashSet<>();
        c.conversion().currencies().forEach(cc -> {
            cc.rows().forEach(r -> cols.addAll(r.values().keySet()));
            cols.addAll(cc.totals().keySet());
        });
        StringBuilder out = new StringBuilder(original).append("\r\n");
        List<String> head = new ArrayList<>(List.of("converted_to", "source_currency", "period", "status", "rate", "rate_date", "rate_source", "rate_age_days"));
        head.addAll(cols);
        Csv.row(out, head);
        for (CurrencyConversion cc : c.conversion().currencies()) {
            if ("UNAVAILABLE".equals(cc.status())) {
                List<String> cells = new ArrayList<>(List.of(c.conversion().reportingCurrency(), cc.currency(), "", "UNAVAILABLE", "", "", "", ""));
                cols.forEach(k -> cells.add(""));
                Csv.row(out, cells);
                continue;
            }
            for (ConvertedRow r : cc.rows()) {
                List<String> cells = new ArrayList<>(List.of(c.conversion().reportingCurrency(), cc.currency(), r.period(), r.rate().status(), nz(r.rate().rate()),
                        r.rate().rateDate() == null ? "" : r.rate().rateDate().toString(), nz(r.rate().source()), r.rate().ageDays() == null ? "" : r.rate().ageDays().toString()));
                cols.forEach(k -> cells.add(r.values().getOrDefault(k, "")));
                Csv.row(out, cells);
            }
            if (!cc.totals().isEmpty()) {
                List<String> cells = new ArrayList<>(List.of(c.conversion().reportingCurrency(), cc.currency(), "TOTAL", cc.status(), "", "", "", ""));
                cols.forEach(k -> cells.add(cc.totals().getOrDefault(k, "")));
                Csv.row(out, cells);
            }
        }
        if (c.conversion().combinedTotals() != null) {
            List<String> cells = new ArrayList<>(List.of(c.conversion().reportingCurrency(), "ALL", "COMBINED_TOTAL", "CONVERTED", "", "", "", ""));
            cols.forEach(k -> cells.add(c.conversion().combinedTotals().getOrDefault(k, "")));
            Csv.row(out, cells);
        }
        return out.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static Map<String, List<String>> moneyColumns() {
        return new TreeMap<>(ROW_MONEY);
    }
}
