package com.finora.finance.insights;

import com.finora.finance.assistant.AiProvider;
import com.finora.finance.core.Validate;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * A deliberately simple, explainable baseline: the average of the months that had activity in a look-back window of COMPLETE months.
 * It models no trend, no seasonality and no growth, and it says so. A currency is only forecast when at least {@link #MIN_MONTHS} months
 * had activity; sparse windows, missing months and outlier months are reported, never hidden. The range is the mean plus or minus one
 * population standard deviation of the past months: an indicative spread, NOT a statistical confidence interval. Everything is exact
 * decimal arithmetic and deterministic; there is no AI in the numbers.
 */
@Service
public class ForecastService {

    public static final int MIN_MONTHS = 3;
    private static final Set<String> NON_NEGATIVE = Set.of("income", "expense", "refunds", "net_expense", "invoiced_net", "received", "expenses");
    static final List<String> PERSONAL_METRICS = List.of("income", "net_expense", "net");
    static final List<String> BUSINESS_METRICS = List.of("received", "expenses", "net_cash", "invoiced_net");

    public record Month(String month, String value, boolean activity, boolean outlier) {
    }

    public record Coverage(int monthsInWindow, int monthsWithData, String ratio, List<String> missingMonths) {
    }

    public record Projection(String month, String estimate, String low, String high) {
    }

    public record CurrencyForecast(String currency, String status, String message, List<Month> history, Coverage coverage, String estimatePerMonth,
            String median, String low, String high, String confidence, List<String> warnings, List<Projection> projection) {
    }

    public record AiExplanation(String status, String message) {
    }

    public record Forecast(String metric, String kind, String label, String method, LocalDate historyFrom, LocalDate historyTo, int lookbackMonths, int horizonMonths,
            List<String> assumptions, AiExplanation aiExplanation, List<CurrencyForecast> currencies) {
    }

    private final ReportService reports;
    private final AiProvider ai;

    public ForecastService(ReportService reports, AiProvider ai) {
        this.reports = reports;
        this.ai = ai;
    }

    public Forecast forecast(InsightsContext ctx, String metricRaw, Integer lookbackRaw, Integer horizonRaw) {
        ctx.requireReports();
        List<String> metrics = ctx.business() ? BUSINESS_METRICS : PERSONAL_METRICS;
        String metric = metricRaw == null || metricRaw.isBlank() ? metrics.get(2) : metricRaw.trim();
        if (!metrics.contains(metric)) {
            throw Validate.invalid("metric must be one of " + metrics);
        }
        int lookback = lookbackRaw == null ? 6 : lookbackRaw;
        int horizon = horizonRaw == null ? 3 : horizonRaw;
        if (lookback < 3 || lookback > 12) {
            throw Validate.invalid("lookback_months must be between 3 and 12");
        }
        if (horizon < 1 || horizon > 6) {
            throw Validate.invalid("horizon_months must be between 1 and 6");
        }
        YearMonth last = YearMonth.from(ctx.today()).minusMonths(1); // the current month is incomplete and never used as history
        YearMonth first = last.minusMonths(lookback - 1L);
        var data = reports.monthly(ctx, first.atDay(1), last.atEndOfMonth());
        List<CurrencyForecast> out = new ArrayList<>();
        for (var e : new TreeMap<>(data).entrySet()) {
            String cur = e.getKey();
            List<Month> history = new ArrayList<>();
            List<BigDecimal> active = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
                var bucket = e.getValue().get(m.atDay(1));
                boolean has = bucket != null && ReportService.activity(bucket, metric);
                BigDecimal v = has ? ReportService.value(bucket, metric) : BigDecimal.ZERO;
                if (has) {
                    active.add(v);
                } else {
                    missing.add(m.toString());
                }
                history.add(new Month(m.toString(), Currencies.format(v, cur), has, false));
            }
            int n = active.size();
            String ratio = BigDecimal.valueOf(n).divide(BigDecimal.valueOf(lookback), 2, RoundingMode.HALF_EVEN).toPlainString();
            Coverage coverage = new Coverage(lookback, n, ratio, missing);
            if (n < MIN_MONTHS) {
                out.add(new CurrencyForecast(cur, "INSUFFICIENT_HISTORY", "Only " + n + " of the last " + lookback + " complete months had activity in " + cur
                        + "; at least " + MIN_MONTHS + " are needed before a baseline estimate is shown.", history, coverage, null, null, null, null, null,
                        List.of(), List.of()));
                continue;
            }
            int exp = Currencies.exponentOrThrow(cur);
            BigDecimal sum = active.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal mean = sum.divide(BigDecimal.valueOf(n), exp + 6, RoundingMode.HALF_EVEN);
            BigDecimal variance = active.stream().map(v -> v.subtract(mean).pow(2)).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(n), 12, RoundingMode.HALF_EVEN);
            BigDecimal sd = variance.sqrt(new MathContext(20, RoundingMode.HALF_EVEN));
            List<String> warnings = new ArrayList<>();
            int outliers = 0;
            if (n >= 4 && sd.signum() > 0) {
                BigDecimal limit = sd.multiply(BigDecimal.valueOf(2));
                List<Month> marked = new ArrayList<>();
                for (Month m : history) {
                    boolean out2 = m.activity() && new BigDecimal(m.value()).subtract(mean).abs().compareTo(limit) > 0;
                    outliers += out2 ? 1 : 0;
                    marked.add(new Month(m.month(), m.value(), m.activity(), out2));
                }
                history = marked;
            }
            if (!missing.isEmpty()) {
                warnings.add(missing.size() + " month(s) had no activity or no data and were left out of the average: " + String.join(", ", missing) + ".");
            }
            if (outliers > 0) {
                warnings.add(outliers + " month(s) are more than 2 standard deviations from the average. They are included; the median is shown as an alternative.");
            }
            boolean volatileHistory = mean.signum() != 0 && sd.divide(mean.abs(), 6, RoundingMode.HALF_EVEN).compareTo(new BigDecimal("0.5")) > 0;
            if (volatileHistory) {
                warnings.add("Monthly values vary a lot (spread above half of the average), so the estimate is a rough guide.");
            }
            boolean low = new BigDecimal(ratio).compareTo(new BigDecimal("0.67")) < 0 || outliers > 0 || volatileHistory;
            BigDecimal estimate = mean.setScale(exp, RoundingMode.HALF_EVEN);
            BigDecimal lo = mean.subtract(sd).setScale(exp, RoundingMode.HALF_EVEN);
            BigDecimal hi = mean.add(sd).setScale(exp, RoundingMode.HALF_EVEN);
            if (NON_NEGATIVE.contains(metric) && lo.signum() < 0) {
                lo = BigDecimal.ZERO.setScale(exp);
            }
            List<BigDecimal> sorted = active.stream().sorted().toList();
            BigDecimal median = n % 2 == 1 ? sorted.get(n / 2)
                    : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), exp, RoundingMode.HALF_EVEN);
            List<Projection> projection = new ArrayList<>();
            for (int i = 1; i <= horizon; i++) {
                projection.add(new Projection(YearMonth.from(ctx.today()).plusMonths(i - 1L).toString(), Currencies.format(estimate, cur), Currencies.format(lo, cur), Currencies.format(hi, cur)));
            }
            out.add(new CurrencyForecast(cur, "ESTIMATE", null, history, coverage, Currencies.format(estimate, cur), Currencies.format(median, cur),
                    Currencies.format(lo, cur), Currencies.format(hi, cur), low ? "LOW" : "MODERATE", warnings, projection));
        }
        return new Forecast(metric, "ESTIMATE", "Estimate, not an actual result and not a guarantee", "Average of months with activity (flat baseline)",
                first.atDay(1), last.atEndOfMonth(), lookback, horizon, List.of(
                        "History is the last " + lookback + " complete months in the workspace time zone; the current month is excluded.",
                        "The estimate is the mean of the months that had activity; months without activity are treated as missing, not as zero.",
                        "No trend, seasonality, growth, known future invoices, bills or recurring items are modelled.",
                        "The range is the mean plus or minus one standard deviation of past months, an indicative spread and not a confidence interval.",
                        "Each currency is forecast on its own; nothing is converted."),
                ai.configured()
                        ? new AiExplanation("IN_ASSISTANT", "An AI provider is configured: ask the Assistant to explain this forecast. The numbers above are plain arithmetic on your recorded data and never use AI.")
                        : new AiExplanation("UNAVAILABLE", "No AI provider is configured, so there is no AI explanation. The numbers above are plain arithmetic on your recorded data."), out);
    }
}
