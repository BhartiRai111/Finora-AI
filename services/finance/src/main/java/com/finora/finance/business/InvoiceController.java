package com.finora.finance.business;

import com.finora.finance.core.Idem;
import jakarta.servlet.http.HttpServletRequest;
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
public class InvoiceController {

    private final BusinessAccess access;
    private final InvoiceService invoices;
    private final Idem idem;

    public InvoiceController(BusinessAccess access, InvoiceService invoices, Idem idem) {
        this.access = access;
        this.invoices = invoices;
        this.idem = idem;
    }

    @GetMapping("/v1/spaces/{space}/invoices")
    public Page<InvoiceService.Invoice> list(@PathVariable UUID space, @RequestParam(required = false) String status, @RequestParam(name = "client_id", required = false) UUID clientId,
            @RequestParam(name = "project_id", required = false) UUID projectId, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(name = "due_from", required = false) String dueFrom, @RequestParam(name = "due_to", required = false) String dueTo,
            @RequestParam(required = false) String q, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset,
            HttpServletRequest request) {
        return invoices.list(access.resolve(request, space), status, clientId, projectId, from, to, dueFrom, dueTo, q, limit, offset);
    }

    @PostMapping("/v1/spaces/{space}/invoices")
    public ResponseEntity<String> create(@PathVariable UUID space, @RequestBody(required = false) InvoiceService.CreateRequest body, HttpServletRequest request) {
        BusinessContext ctx = access.resolve(request, space);
        InvoiceService.CreateRequest b = body == null ? new InvoiceService.CreateRequest(null, null, null, null, null, null, null, null) : body;
        return idem.run(request, ctx.tenantId(), b, false, HttpStatus.CREATED, () -> invoices.create(ctx, b));
    }

    @GetMapping("/v1/spaces/{space}/invoices/{id}")
    public InvoiceService.Invoice get(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return invoices.get(access.resolve(request, space), id);
    }

    @PatchMapping("/v1/spaces/{space}/invoices/{id}")
    public InvoiceService.Invoice update(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) InvoiceService.UpdateRequest body,
            HttpServletRequest request) {
        return invoices.update(access.resolve(request, space), id,
                body == null ? new InvoiceService.UpdateRequest(null, null, null, null, null, null, null, null) : body);
    }

    @PostMapping("/v1/spaces/{space}/invoices/{id}/issue")
    public ResponseEntity<String> issue(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) InvoiceService.IssueRequest body,
            HttpServletRequest request) {
        BusinessContext ctx = access.resolve(request, space);
        InvoiceService.IssueRequest b = body == null ? new InvoiceService.IssueRequest(null, null) : body;
        return idem.run(request, ctx.tenantId(), b, true, HttpStatus.OK, () -> invoices.issue(ctx, id, b));
    }

    @PostMapping("/v1/spaces/{space}/invoices/{id}/cancel")
    public ResponseEntity<String> cancel(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) InvoiceService.CancelRequest body,
            HttpServletRequest request) {
        BusinessContext ctx = access.resolve(request, space);
        InvoiceService.CancelRequest b = body == null ? new InvoiceService.CancelRequest(null, null) : body;
        return idem.run(request, ctx.tenantId(), b, true, HttpStatus.OK, () -> invoices.cancel(ctx, id, b));
    }
}
