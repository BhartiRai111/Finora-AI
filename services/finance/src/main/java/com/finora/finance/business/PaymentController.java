package com.finora.finance.business;

import com.finora.finance.core.Idem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private final BusinessAccess access;
    private final PaymentService payments;
    private final Idem idem;

    public PaymentController(BusinessAccess access, PaymentService payments, Idem idem) {
        this.access = access;
        this.payments = payments;
        this.idem = idem;
    }

    @GetMapping("/v1/spaces/{space}/payments")
    public Page<PaymentService.Payment> list(@PathVariable UUID space, @RequestParam(name = "client_id", required = false) UUID clientId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String method, @RequestParam(required = false) String q,
            @RequestParam(name = "include_voided", defaultValue = "false") boolean includeVoided, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset, HttpServletRequest request) {
        return payments.list(access.resolve(request, space), clientId, from, to, method, q, includeVoided, limit, offset);
    }

    /** Recording a payment always needs an Idempotency-Key: a retry must never record the money twice. */
    @PostMapping("/v1/spaces/{space}/payments")
    public ResponseEntity<String> record(@PathVariable UUID space, @RequestBody(required = false) PaymentService.Request body, HttpServletRequest request) {
        BusinessContext ctx = access.resolve(request, space);
        PaymentService.Request b = body == null ? new PaymentService.Request(null, null, null, null, null, null, null, null, null) : body;
        return idem.run(request, ctx.tenantId(), b, true, HttpStatus.CREATED, () -> payments.record(ctx, b));
    }

    @GetMapping("/v1/spaces/{space}/payments/{id}")
    public PaymentService.Payment get(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return payments.get(access.resolve(request, space), id);
    }

    @PostMapping("/v1/spaces/{space}/payments/{id}/allocations")
    public ResponseEntity<String> allocate(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) PaymentService.AllocateRequest body,
            HttpServletRequest request) {
        BusinessContext ctx = access.resolve(request, space);
        PaymentService.AllocateRequest b = body == null ? new PaymentService.AllocateRequest(null) : body;
        return idem.run(request, ctx.tenantId(), b, true, HttpStatus.OK, () -> payments.allocateMore(ctx, id, b));
    }

    @PostMapping("/v1/spaces/{space}/payments/{id}/void")
    public ResponseEntity<String> voidPayment(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) PaymentService.VoidRequest body,
            HttpServletRequest request) {
        BusinessContext ctx = access.resolve(request, space);
        PaymentService.VoidRequest b = body == null ? new PaymentService.VoidRequest(null) : body;
        return idem.run(request, ctx.tenantId(), b, true, HttpStatus.OK, () -> payments.voidPayment(ctx, id, b));
    }
}
