package com.finora.finance.budgets;

import com.finora.finance.core.FinanceAccess;
import com.finora.finance.core.FinanceContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BudgetController {

    private final FinanceAccess access;
    private final BudgetService budgets;

    public BudgetController(FinanceAccess access, BudgetService budgets) {
        this.access = access;
        this.budgets = budgets;
    }

    @GetMapping("/v1/spaces/{space}/budgets")
    public BudgetService.Summary summary(@PathVariable UUID space, @RequestParam(required = false) String month, HttpServletRequest request) {
        return budgets.summary(access.resolve(request, space), month);
    }

    @PostMapping("/v1/spaces/{space}/budgets")
    public ResponseEntity<BudgetService.View> create(@PathVariable UUID space, @RequestBody(required = false) BudgetService.CreateRequest body,
            HttpServletRequest request) {
        FinanceContext ctx = access.resolve(request, space);
        return ResponseEntity.status(201).body(budgets.create(ctx, body == null ? new BudgetService.CreateRequest(null, null, null) : body));
    }

    @PatchMapping("/v1/spaces/{space}/budgets/{id}")
    public BudgetService.View update(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) BudgetService.UpdateRequest body,
            HttpServletRequest request) {
        return budgets.update(access.resolve(request, space), id, body == null ? new BudgetService.UpdateRequest(null, null) : body);
    }

    @PostMapping("/v1/spaces/{space}/budgets/{id}/archive")
    public ResponseEntity<Void> archive(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        budgets.archive(access.resolve(request, space), id);
        return ResponseEntity.noContent().build();
    }
}
