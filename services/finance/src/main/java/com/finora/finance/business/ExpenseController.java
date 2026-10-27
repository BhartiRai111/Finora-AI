package com.finora.finance.business;

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
public class ExpenseController {

    private final BusinessAccess access;
    private final ExpenseService expenses;
    private final Idem idem;

    public ExpenseController(BusinessAccess access, ExpenseService expenses, Idem idem) {
        this.access = access;
        this.expenses = expenses;
        this.idem = idem;
    }

    private static ExpenseService.Request none() {
        return new ExpenseService.Request(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @GetMapping("/v1/spaces/{space}/expenses")
    public Page<ExpenseService.Expense> list(@PathVariable UUID space, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(name = "vendor_id", required = false) UUID vendorId, @RequestParam(name = "project_id", required = false) UUID projectId,
            @RequestParam(name = "category_id", required = false) UUID categoryId, @RequestParam(required = false) String q,
            @RequestParam(name = "include_voided", defaultValue = "false") boolean includeVoided, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset, HttpServletRequest request) {
        return expenses.list(access.resolve(request, space), from, to, vendorId, projectId, categoryId, q, includeVoided, limit, offset);
    }

    @PostMapping("/v1/spaces/{space}/expenses")
    public ResponseEntity<String> create(@PathVariable UUID space, @RequestBody(required = false) ExpenseService.Request body, HttpServletRequest request) {
        BusinessContext ctx = access.resolve(request, space);
        ExpenseService.Request b = body == null ? none() : body;
        return idem.run(request, ctx.tenantId(), b, false, HttpStatus.CREATED, () -> expenses.create(ctx, b));
    }

    @GetMapping("/v1/spaces/{space}/expenses/{id}")
    public ExpenseService.Expense get(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return expenses.get(access.resolve(request, space), id);
    }

    @PatchMapping("/v1/spaces/{space}/expenses/{id}")
    public ExpenseService.Expense update(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) ExpenseService.Request body,
            HttpServletRequest request) {
        return expenses.update(access.resolve(request, space), id, body == null ? none() : body);
    }

    @PostMapping("/v1/spaces/{space}/expenses/{id}/void")
    public ExpenseService.Expense voidExpense(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) ExpenseService.VoidRequest body,
            HttpServletRequest request) {
        return expenses.voidExpense(access.resolve(request, space), id, body == null ? new ExpenseService.VoidRequest(null, null) : body);
    }

    @GetMapping("/v1/spaces/{space}/expense-categories")
    public Map<String, List<ExpenseService.Category>> categories(@PathVariable UUID space,
            @RequestParam(name = "include_archived", defaultValue = "false") boolean includeArchived, HttpServletRequest request) {
        return Map.of("items", expenses.categories(access.resolve(request, space), includeArchived));
    }

    @PostMapping("/v1/spaces/{space}/expense-categories")
    public ResponseEntity<String> createCategory(@PathVariable UUID space, @RequestBody(required = false) ExpenseService.CategoryRequest body, HttpServletRequest request) {
        BusinessContext ctx = access.resolve(request, space);
        ExpenseService.CategoryRequest b = body == null ? new ExpenseService.CategoryRequest(null, null) : body;
        return idem.run(request, ctx.tenantId(), b, false, HttpStatus.CREATED, () -> expenses.createCategory(ctx, b));
    }

    @PatchMapping("/v1/spaces/{space}/expense-categories/{id}")
    public ExpenseService.Category renameCategory(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) ExpenseService.CategoryRequest body,
            HttpServletRequest request) {
        return expenses.updateCategory(access.resolve(request, space), id, body == null ? new ExpenseService.CategoryRequest(null, null) : body, null);
    }

    @PostMapping("/v1/spaces/{space}/expense-categories/{id}/archive")
    public ExpenseService.Category archiveCategory(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return expenses.updateCategory(access.resolve(request, space), id, null, true);
    }

    @PostMapping("/v1/spaces/{space}/expense-categories/{id}/restore")
    public ExpenseService.Category restoreCategory(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return expenses.updateCategory(access.resolve(request, space), id, null, false);
    }
}
