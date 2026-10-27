package com.finora.finance.accounting;

import com.finora.finance.insights.InsightsAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountingController {

    private final InsightsAccess access;
    private final AccountingService accounting;

    public AccountingController(InsightsAccess access, AccountingService accounting) {
        this.access = access;
        this.accounting = accounting;
    }

    @GetMapping("/v1/spaces/{space}/finance-accounting")
    public Map<String, Object> status(@PathVariable UUID space, HttpServletRequest request) {
        return accounting.status(access.resolve(request, space));
    }

    @GetMapping("/v1/spaces/{space}/finance-accounting/sync-runs")
    public Map<String, List<Map<String, Object>>> syncRuns(@PathVariable UUID space, HttpServletRequest request) {
        return Map.of("items", accounting.syncRuns(access.resolve(request, space)));
    }
}
