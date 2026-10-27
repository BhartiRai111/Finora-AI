package com.finora.finance.regional;

import com.finora.finance.insights.InsightsAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RegionalController {

    private final InsightsAccess access;
    private final RegionalService regional;

    public RegionalController(InsightsAccess access, RegionalService regional) {
        this.access = access;
        this.regional = regional;
    }

    @GetMapping("/v1/spaces/{space}/finance-regional")
    public Map<String, Object> get(@PathVariable UUID space, HttpServletRequest request) {
        return regional.get(access.resolve(request, space));
    }

    @PutMapping("/v1/spaces/{space}/finance-regional")
    public Map<String, Object> put(@PathVariable UUID space, @RequestBody(required = false) RegionalService.UpdateRequest body, HttpServletRequest request) {
        return regional.update(access.resolve(request, space), body == null ? new RegionalService.UpdateRequest(null, null, null, null, null, null, null, null, null, null) : body);
    }

    @GetMapping("/v1/spaces/{space}/finance-regional/periods")
    public Map<String, Object> periods(@PathVariable UUID space, HttpServletRequest request) {
        return regional.periods(access.resolve(request, space));
    }
}
