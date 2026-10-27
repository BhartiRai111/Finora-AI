package com.finora.finance.fx;

import com.finora.finance.insights.InsightsAccess;
import com.finora.finance.insights.ReportService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exchange rates (entered by people; no provider is configured) and reports shown in a reporting currency. */
@RestController
public class FxController {

    public record VoidRequest(String reason) {
    }

    private final InsightsAccess access;
    private final RateService rates;
    private final ConversionService conversion;

    public FxController(InsightsAccess access, RateService rates, ConversionService conversion) {
        this.access = access;
        this.rates = rates;
        this.conversion = conversion;
    }

    @GetMapping("/v1/spaces/{space}/finance-rates/status")
    public Map<String, Object> status(@PathVariable UUID space, HttpServletRequest request) {
        var ctx = access.resolve(request, space);
        ctx.requireRegionalRead();
        return Map.of("automatic_source", Map.of("status", "NOT_CONFIGURED", "message", "No exchange-rate provider has been approved or configured, so no rate is fetched automatically."),
                "manual_entry", Map.of("available", true, "source", "USER_SUPPLIED", "can_write", canWrite(ctx)),
                "stale_after_days", ctx.rateStaleAfterDays(), "notes", List.of("Only direct pairs are used; inverse and cross rates are never derived.",
                        "A rate older than the stale threshold is shown as STALE and is not applied unless you explicitly accept stale rates."));
    }

    private static boolean canWrite(com.finora.finance.insights.InsightsContext ctx) {
        try {
            ctx.requireSettingsWrite();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @GetMapping("/v1/spaces/{space}/finance-rates")
    public Map<String, List<RateService.RateView>> list(@PathVariable UUID space, @RequestParam(required = false) String base, @RequestParam(required = false) String quote,
            @RequestParam(name = "include_voided", defaultValue = "false") boolean includeVoided, @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset, HttpServletRequest request) {
        return Map.of("items", rates.list(access.resolve(request, space), base, quote, includeVoided, limit, offset));
    }

    @PostMapping("/v1/spaces/{space}/finance-rates")
    public org.springframework.http.ResponseEntity<RateService.RateView> create(@PathVariable UUID space, @RequestBody(required = false) RateService.CreateRequest body, HttpServletRequest request) {
        return org.springframework.http.ResponseEntity.status(201).body(rates.create(access.resolve(request, space),
                body == null ? new RateService.CreateRequest(null, null, null, null, null) : body));
    }

    @PostMapping("/v1/spaces/{space}/finance-rates/{id}/void")
    public RateService.RateView voidRate(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) VoidRequest body, HttpServletRequest request) {
        return rates.voidRate(access.resolve(request, space), id, body == null ? null : body.reason());
    }

    @GetMapping("/v1/spaces/{space}/finance-rates/lookup")
    public RateService.Lookup lookup(@PathVariable UUID space, @RequestParam String base, @RequestParam String quote, @RequestParam(required = false) String date,
            HttpServletRequest request) {
        return rates.lookup(access.resolve(request, space), base, quote, date);
    }

    @GetMapping("/v1/spaces/{space}/finance-reports/{name}/converted")
    public ConversionService.Converted converted(@PathVariable UUID space, @PathVariable String name, @RequestParam(name = "reporting_currency") String reportingCurrency,
            @RequestParam(name = "allow_stale", defaultValue = "false") boolean allowStale, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity, @RequestParam(required = false) String month,
            @RequestParam(name = "as_of", required = false) String asOf, HttpServletRequest request) {
        return conversion.convert(access.resolve(request, space), name, new ReportService.Params(from, to, granularity, null, null, null, month, asOf), reportingCurrency, allowStale);
    }
}
