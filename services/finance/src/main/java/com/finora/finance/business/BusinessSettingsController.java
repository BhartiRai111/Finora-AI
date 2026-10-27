package com.finora.finance.business;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BusinessSettingsController {

    private final BusinessAccess access;
    private final BusinessSettingsService settings;

    public BusinessSettingsController(BusinessAccess access, BusinessSettingsService settings) {
        this.access = access;
        this.settings = settings;
    }

    @GetMapping("/v1/spaces/{space}/business-settings")
    public BusinessSettingsService.Settings get(@PathVariable UUID space, HttpServletRequest request) {
        return settings.get(access.resolve(request, space));
    }

    @PutMapping("/v1/spaces/{space}/business-settings")
    public BusinessSettingsService.Settings put(@PathVariable UUID space, @RequestBody(required = false) BusinessSettingsService.UpdateRequest body,
            HttpServletRequest request) {
        return settings.update(access.resolve(request, space),
                body == null ? new BusinessSettingsService.UpdateRequest(null, null, null, null, null, null, null) : body);
    }
}
