package com.finora.finance.alerts;

import com.finora.finance.insights.InsightsAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AlertController {

    public record ResolveRequest(String reason) {
    }

    private final InsightsAccess access;
    private final AlertService alerts;

    public AlertController(InsightsAccess access, AlertService alerts) {
        this.access = access;
        this.alerts = alerts;
    }

    @GetMapping("/v1/spaces/{space}/finance-alerts")
    public Map<String, Object> list(@PathVariable UUID space, @RequestParam(required = false) String status, @RequestParam(required = false) String type, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset, HttpServletRequest request) {
        return alerts.list(access.resolve(request, space), status, type, limit, offset);
    }

    @GetMapping("/v1/spaces/{space}/finance-alerts/settings")
    public Map<String, List<AlertService.Setting>> settings(@PathVariable UUID space, HttpServletRequest request) {
        return Map.of("items", alerts.settings(access.resolve(request, space)));
    }

    @PutMapping("/v1/spaces/{space}/finance-alerts/settings/{type}")
    public AlertService.Setting putSetting(@PathVariable UUID space, @PathVariable String type, @RequestBody(required = false) AlertService.SettingRequest body, HttpServletRequest request) {
        return alerts.putSetting(access.resolve(request, space), type, body == null ? new AlertService.SettingRequest(null, null, null, null) : body);
    }

    @PostMapping("/v1/spaces/{space}/finance-alerts/evaluate")
    public Map<String, Object> evaluate(@PathVariable UUID space, HttpServletRequest request) {
        return alerts.evaluate(access.resolve(request, space));
    }

    @GetMapping("/v1/spaces/{space}/finance-alerts/{id}")
    public AlertService.Alert get(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return alerts.get(access.resolve(request, space), id);
    }

    @PostMapping("/v1/spaces/{space}/finance-alerts/{id}/acknowledge")
    public AlertService.Alert acknowledge(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return alerts.acknowledge(access.resolve(request, space), id);
    }

    @PostMapping("/v1/spaces/{space}/finance-alerts/{id}/resolve")
    public AlertService.Alert resolve(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) ResolveRequest body, HttpServletRequest request) {
        return alerts.resolve(access.resolve(request, space), id, body == null ? null : body.reason());
    }
}
