package com.finora.finance.integrations;

import com.finora.finance.insights.InsightsAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IntegrationController {

    private final InsightsAccess access;
    private final IntegrationService integrations;

    public IntegrationController(InsightsAccess access, IntegrationService integrations) {
        this.access = access;
        this.integrations = integrations;
    }

    @GetMapping("/v1/spaces/{space}/finance-integrations")
    public Map<String, List<IntegrationService.Integration>> registry(@PathVariable UUID space, HttpServletRequest request) {
        return Map.of("items", integrations.registry(access.resolve(request, space)));
    }

    @GetMapping("/v1/spaces/{space}/finance-integrations/{key}/errors")
    public Map<String, List<Map<String, Object>>> errors(@PathVariable UUID space, @PathVariable String key, HttpServletRequest request) {
        return Map.of("items", integrations.errors(access.resolve(request, space), key));
    }

    @GetMapping("/v1/spaces/{space}/finance-admin/overview")
    public Map<String, Object> overview(@PathVariable UUID space, HttpServletRequest request) {
        return integrations.adminOverview(access.resolve(request, space));
    }
}
