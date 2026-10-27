package com.finora.finance.insights;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {

    private final InsightsAccess access;
    private final ReportService reports;
    private final ForecastService forecasts;
    private final com.finora.finance.fx.ConversionService conversion;

    public ReportController(InsightsAccess access, ReportService reports, ForecastService forecasts, com.finora.finance.fx.ConversionService conversion) {
        this.access = access;
        this.reports = reports;
        this.forecasts = forecasts;
        this.conversion = conversion;
    }

    private static ReportService.Params params(String from, String to, String granularity, String compare, String compareFrom, String compareTo, String month, String asOf) {
        return new ReportService.Params(from, to, granularity, compare, compareFrom, compareTo, month, asOf);
    }

    @GetMapping("/v1/spaces/{space}/finance-reports")
    public Map<String, List<String>> available(@PathVariable UUID space, HttpServletRequest request) {
        InsightsContext ctx = access.resolve(request, space);
        ctx.requireReports();
        return Map.of("reports", ctx.business() ? ReportService.BUSINESS_REPORTS : ReportService.PERSONAL_REPORTS,
                "forecast_metrics", ctx.business() ? ForecastService.BUSINESS_METRICS : ForecastService.PERSONAL_METRICS);
    }

    @GetMapping("/v1/spaces/{space}/finance-reports/{name}")
    public ReportModel.Report get(@PathVariable UUID space, @PathVariable String name, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity, @RequestParam(required = false) String compare, @RequestParam(name = "compare_from", required = false) String compareFrom,
            @RequestParam(name = "compare_to", required = false) String compareTo, @RequestParam(required = false) String month,
            @RequestParam(name = "as_of", required = false) String asOf, HttpServletRequest request) {
        return reports.report(access.resolve(request, space), name, params(from, to, granularity, compare, compareFrom, compareTo, month, asOf));
    }

    @GetMapping("/v1/spaces/{space}/finance-reports/{name}/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID space, @PathVariable String name, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity, @RequestParam(required = false) String compare, @RequestParam(name = "compare_from", required = false) String compareFrom,
            @RequestParam(name = "compare_to", required = false) String compareTo, @RequestParam(required = false) String month,
            @RequestParam(name = "as_of", required = false) String asOf, @RequestParam(name = "reporting_currency", required = false) String reportingCurrency,
            @RequestParam(name = "allow_stale", defaultValue = "false") boolean allowStale, HttpServletRequest request) {
        InsightsContext ctx = access.resolve(request, space);
        ReportService.Params p = params(from, to, granularity, compare, compareFrom, compareTo, month, asOf);
        String csv = reportingCurrency == null || reportingCurrency.isBlank() ? reports.exportCsv(ctx, name, p) : conversion.exportCsv(ctx, name, p, reportingCurrency, allowStale);
        ReportModel.Report r = reports.report(ctx, name, p);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + ReportService.fileName(name, r) + "\"")
                .header("X-Content-Type-Options", "nosniff").body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/v1/spaces/{space}/finance-forecast")
    public ForecastService.Forecast forecast(@PathVariable UUID space, @RequestParam(required = false) String metric,
            @RequestParam(name = "lookback_months", required = false) Integer lookback, @RequestParam(name = "horizon_months", required = false) Integer horizon,
            HttpServletRequest request) {
        return forecasts.forecast(access.resolve(request, space), metric, lookback, horizon);
    }
}
