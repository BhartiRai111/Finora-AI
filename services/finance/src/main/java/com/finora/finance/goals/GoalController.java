package com.finora.finance.goals;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GoalController {

    private final FinanceAccess access;
    private final GoalService goals;
    private final Idem idem;

    public GoalController(FinanceAccess access, GoalService goals, Idem idem) {
        this.access = access;
        this.goals = goals;
        this.idem = idem;
    }

    @GetMapping("/v1/spaces/{space}/goals")
    public Map<String, List<GoalService.View>> list(@PathVariable UUID space,
            @RequestParam(name = "include_archived", defaultValue = "false") boolean includeArchived, HttpServletRequest request) {
        return Map.of("items", goals.list(access.resolve(request, space), includeArchived));
    }

    @GetMapping("/v1/spaces/{space}/goals/{id}")
    public GoalService.View get(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return goals.get(access.resolve(request, space), id);
    }

    @PostMapping("/v1/spaces/{space}/goals")
    public ResponseEntity<String> create(@PathVariable UUID space, @RequestBody(required = false) GoalService.CreateRequest body, HttpServletRequest request) {
        FinanceContext ctx = access.resolve(request, space);
        GoalService.CreateRequest b = body == null ? new GoalService.CreateRequest(null, null, null, null) : body;
        return idem.run(request, ctx, b, false, HttpStatus.CREATED, () -> goals.create(ctx, b));
    }

    @PatchMapping("/v1/spaces/{space}/goals/{id}")
    public GoalService.View update(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) GoalService.UpdateRequest body,
            HttpServletRequest request) {
        return goals.update(access.resolve(request, space), id, body == null ? new GoalService.UpdateRequest(null, null, null, null, null) : body);
    }

    @PostMapping("/v1/spaces/{space}/goals/{id}/archive")
    public GoalService.View archive(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return goals.setArchived(access.resolve(request, space), id, true);
    }

    @PostMapping("/v1/spaces/{space}/goals/{id}/restore")
    public GoalService.View restore(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return goals.setArchived(access.resolve(request, space), id, false);
    }

    @PostMapping("/v1/spaces/{space}/goals/{id}/contributions")
    public ResponseEntity<String> contribute(@PathVariable UUID space, @PathVariable UUID id,
            @RequestBody(required = false) GoalService.ContributionRequest body, HttpServletRequest request) {
        FinanceContext ctx = access.resolve(request, space);
        GoalService.ContributionRequest b = body == null ? new GoalService.ContributionRequest(null, null, null) : body;
        return idem.run(request, ctx, b, true, HttpStatus.CREATED, () -> goals.contribute(ctx, id, b));
    }
}
