package com.finora.finance.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SettingsController {

    public record SettingsRequest(String timezone) {
    }

    private final FinanceAccess access;
    private final SettingsService settings;

    public SettingsController(FinanceAccess access, SettingsService settings) {
        this.access = access;
        this.settings = settings;
    }

    @GetMapping("/v1/spaces/{space}/finance-settings")
    public Map<String, Object> get(@PathVariable UUID space, HttpServletRequest request) {
        return settings.get(access.resolve(request, space));
    }

    @PutMapping("/v1/spaces/{space}/finance-settings")
    public Map<String, Object> put(@PathVariable UUID space, @RequestBody(required = false) SettingsRequest body, HttpServletRequest request) {
        return settings.update(access.resolve(request, space), body == null ? null : body.timezone());
    }
}
