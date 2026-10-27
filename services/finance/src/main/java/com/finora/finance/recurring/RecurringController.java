package com.finora.finance.recurring;

import com.finora.finance.core.FinanceAccess;
import com.finora.finance.core.FinanceContext;
import com.finora.finance.core.Idem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RecurringController {

    private final FinanceAccess access;
    private final RecurringService recurring;
    private final Idem idem;

    public RecurringController(FinanceAccess access, RecurringService recurring, Idem idem) {
        this.access = access;
        this.recurring = recurring;
        this.idem = idem;
    }

    @GetMapping("/v1/spaces/{space}/recurring-rules")
    public Map<String, List<RecurringService.View>> list(@PathVariable UUID space, HttpServletRequest request) {
        return Map.of("items", recurring.list(access.resolve(request, space)));
    }

    @PostMapping("/v1/spaces/{space}/recurring-rules")
    public ResponseEntity<String> create(@PathVariable UUID space, @RequestBody(required = false) RecurringService.CreateRequest body,
            HttpServletRequest request) {
        FinanceContext ctx = access.resolve(request, space);
        RecurringService.CreateRequest b = body == null ? new RecurringService.CreateRequest(null, null, null, null, null, null, null, null, null, null, null) : body;
        return idem.run(request, ctx, b, false, HttpStatus.CREATED, () -> recurring.create(ctx, b));
    }

    @PatchMapping("/v1/spaces/{space}/recurring-rules/{id}")
    public RecurringService.View update(@PathVariable UUID space, @PathVariable UUID id,
            @RequestBody(required = false) RecurringService.UpdateRequest body, HttpServletRequest request) {
        RecurringService.UpdateRequest b = body == null ? new RecurringService.UpdateRequest(null, null, null, null, null, null, null, null, null) : body;
        return recurring.update(access.resolve(request, space), id, b);
    }

    @PostMapping("/v1/spaces/{space}/recurring-rules/{id}/pause")
    public RecurringService.View pause(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return recurring.pause(access.resolve(request, space), id);
    }

    @PostMapping("/v1/spaces/{space}/recurring-rules/{id}/resume")
    public RecurringService.View resume(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return recurring.resume(access.resolve(request, space), id);
    }

    @PostMapping("/v1/spaces/{space}/recurring-rules/run")
    public Map<String, Integer> run(@PathVariable UUID space, HttpServletRequest request) {
        return Map.of("created", recurring.generateDue(access.resolve(request, space)));
    }
}
