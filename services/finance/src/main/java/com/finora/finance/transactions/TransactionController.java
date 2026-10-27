package com.finora.finance.transactions;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.core.FinanceAccess;
import com.finora.finance.core.FinanceContext;
import com.finora.finance.core.Idem;
import com.finora.finance.core.Validate;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
public class TransactionController {

    private final FinanceAccess access;
    private final TransactionService transactions;
    private final Idem idem;

    public TransactionController(FinanceAccess access, TransactionService transactions, Idem idem) {
        this.access = access;
        this.transactions = transactions;
        this.idem = idem;
    }

    @GetMapping("/v1/spaces/{space}/transactions")
    public TransactionService.Page list(@PathVariable UUID space, HttpServletRequest request,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(name = "account_id", required = false) String accountId,
            @RequestParam(name = "category_id", required = false) String categoryId,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String status,
            @RequestParam(name = "include_voided", defaultValue = "false") boolean includeVoided,
            @RequestParam(required = false) String tags, @RequestParam(name = "tag_mode", defaultValue = "all") String tagMode,
            @RequestParam(required = false) String q, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        FinanceContext ctx = access.resolve(request, space);
        String k = TransactionService.normalizeKind(kind);
        if (k != null && !List.of("INCOME", "EXPENSE", "TRANSFER", "REFUND", "ADJUSTMENT").contains(k)) {
            throw Validate.invalid("kind is not a valid choice");
        }
        String s = TransactionService.normalizeKind(status);
        if (s != null && !List.of("DRAFT", "POSTED", "VOIDED").contains(s)) {
            throw Validate.invalid("status is not a valid choice");
        }
        if (!List.of("all", "any").contains(tagMode.toLowerCase(Locale.ROOT))) {
            throw Validate.invalid("tag_mode must be all or any");
        }
        List<String> tagList = tags == null || tags.isBlank() ? List.of() : Validate.tags(Arrays.asList(tags.split(",")));
        TransactionService.Filter filter = new TransactionService.Filter(Validate.date("from", from, false), Validate.date("to", to, false),
                Validate.uuid("account_id", accountId, false), Validate.uuid("category_id", categoryId, false), k, s, includeVoided,
                tagList, "any".equalsIgnoreCase(tagMode), q, cursor, limit);
        return transactions.list(ctx, filter);
    }

    @GetMapping("/v1/spaces/{space}/transactions/{id}")
    public TransactionService.View get(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return transactions.get(access.resolve(request, space), id);
    }

    @PostMapping("/v1/spaces/{space}/transactions")
    public ResponseEntity<String> create(@PathVariable UUID space, @RequestBody(required = false) TransactionService.CreateRequest body,
            HttpServletRequest request) {
        FinanceContext ctx = access.resolve(request, space);
        if (body == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Request body is required");
        }
        return idem.run(request, ctx, body, true, HttpStatus.CREATED, () -> transactions.create(ctx, body));
    }

    @PostMapping("/v1/spaces/{space}/transfers")
    public ResponseEntity<String> transfer(@PathVariable UUID space, @RequestBody(required = false) TransactionService.TransferRequest body,
            HttpServletRequest request) {
        FinanceContext ctx = access.resolve(request, space);
        if (body == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Request body is required");
        }
        return idem.run(request, ctx, body, true, HttpStatus.CREATED, () -> transactions.transfer(ctx, body));
    }

    @PatchMapping("/v1/spaces/{space}/transactions/{id}")
    public TransactionService.View edit(@PathVariable UUID space, @PathVariable UUID id,
            @RequestBody(required = false) TransactionService.EditRequest body, HttpServletRequest request) {
        if (body == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Request body is required");
        }
        return transactions.edit(access.resolve(request, space), id, body);
    }

    @PostMapping("/v1/spaces/{space}/transactions/{id}/void")
    public TransactionService.View voidIt(@PathVariable UUID space, @PathVariable UUID id,
            @RequestBody(required = false) TransactionService.VoidRequest body, HttpServletRequest request) {
        return transactions.voidTransaction(access.resolve(request, space), id, body == null ? null : body.reason());
    }

    @PostMapping("/v1/spaces/{space}/transactions/{id}/restore")
    public TransactionService.View restore(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return transactions.restore(access.resolve(request, space), id);
    }

    @PostMapping("/v1/spaces/{space}/transactions/{id}/confirm")
    public TransactionService.View confirm(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return transactions.confirm(access.resolve(request, space), id);
    }

}
