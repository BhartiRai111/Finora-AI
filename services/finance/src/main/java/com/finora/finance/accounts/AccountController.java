package com.finora.finance.accounts;

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
public class AccountController {

    public record RenameRequest(String name, Integer version) {
    }

    private final FinanceAccess access;
    private final AccountService accounts;
    private final Idem idem;

    public AccountController(FinanceAccess access, AccountService accounts, Idem idem) {
        this.access = access;
        this.accounts = accounts;
        this.idem = idem;
    }

    @GetMapping("/v1/spaces/{space}/accounts")
    public Map<String, List<AccountService.View>> list(@PathVariable UUID space, @RequestParam(name = "include_archived", defaultValue = "false") boolean includeArchived,
            HttpServletRequest request) {
        return Map.of("items", accounts.list(access.resolve(request, space), includeArchived));
    }

    @PostMapping("/v1/spaces/{space}/accounts")
    public ResponseEntity<String> create(@PathVariable UUID space, @RequestBody(required = false) AccountService.CreateRequest body, HttpServletRequest request) {
        FinanceContext ctx = access.resolve(request, space);
        AccountService.CreateRequest b = body == null ? new AccountService.CreateRequest(null, null, null, null, null) : body;
        return idem.run(request, ctx, b, false, HttpStatus.CREATED, () -> accounts.create(ctx, b));
    }

    @GetMapping("/v1/spaces/{space}/accounts/{id}")
    public AccountService.View get(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return accounts.get(access.resolve(request, space), id);
    }

    @PatchMapping("/v1/spaces/{space}/accounts/{id}")
    public AccountService.View rename(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) RenameRequest body, HttpServletRequest request) {
        RenameRequest b = body == null ? new RenameRequest(null, null) : body;
        return accounts.rename(access.resolve(request, space), id, b.name(), b.version());
    }

    @PostMapping("/v1/spaces/{space}/accounts/{id}/archive")
    public AccountService.View archive(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return accounts.setArchived(access.resolve(request, space), id, true);
    }

    @PostMapping("/v1/spaces/{space}/accounts/{id}/restore")
    public AccountService.View restore(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return accounts.setArchived(access.resolve(request, space), id, false);
    }

    @PostMapping("/v1/spaces/{space}/accounts/{id}/adjustments")
    public ResponseEntity<String> adjust(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) AccountService.AdjustRequest body,
            HttpServletRequest request) {
        FinanceContext ctx = access.resolve(request, space);
        AccountService.AdjustRequest b = body == null ? new AccountService.AdjustRequest(null, null, null) : body;
        return idem.run(request, ctx, b, true, HttpStatus.CREATED, () -> accounts.adjust(ctx, id, b));
    }
}
