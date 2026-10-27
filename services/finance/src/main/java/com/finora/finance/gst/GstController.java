package com.finora.finance.gst;

import com.finora.finance.insights.InsightsAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GST details and preparation worksheets. Nothing here files, submits or declares anything. */
@RestController
public class GstController {

    private final InsightsAccess access;
    private final GstService gst;

    public GstController(InsightsAccess access, GstService gst) {
        this.access = access;
        this.gst = gst;
    }

    @GetMapping("/v1/spaces/{space}/finance-gst/reference")
    public Map<String, Object> reference(@PathVariable UUID space, HttpServletRequest request) {
        return gst.reference(access.resolve(request, space));
    }

    @GetMapping("/v1/spaces/{space}/finance-gst/registration")
    public GstService.Registration registration(@PathVariable UUID space, HttpServletRequest request) {
        return gst.registration(access.resolve(request, space));
    }

    @PutMapping("/v1/spaces/{space}/finance-gst/registration")
    public GstService.Registration setRegistration(@PathVariable UUID space, @RequestBody(required = false) GstService.RegistrationRequest body, HttpServletRequest request) {
        return gst.setRegistration(access.resolve(request, space), body == null ? new GstService.RegistrationRequest(null, null) : body);
    }

    @GetMapping("/v1/spaces/{space}/finance-gst/summary")
    public Map<String, Object> summary(@PathVariable UUID space, @RequestParam(required = false) String from, @RequestParam(required = false) String to, HttpServletRequest request) {
        return gst.summary(access.resolve(request, space), from, to);
    }

    @GetMapping("/v1/spaces/{space}/finance-gst/transactions")
    public Map<String, Object> transactions(@PathVariable UUID space, @RequestParam(required = false) String from, @RequestParam(required = false) String to, HttpServletRequest request) {
        return gst.transactions(access.resolve(request, space), from, to);
    }

    @GetMapping("/v1/spaces/{space}/finance-gst/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID space, @RequestParam(required = false) String from, @RequestParam(required = false) String to, HttpServletRequest request) {
        var ctx = access.resolve(request, space);
        String csv = gst.exportCsv(ctx, from, to);
        var s = gst.summary(ctx, from, to);
        @SuppressWarnings("unchecked")
        Map<String, String> period = (Map<String, String>) s.get("period");
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + GstService.fileName(period.get("from"), period.get("to")) + "\"")
                .header("X-Content-Type-Options", "nosniff").body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/v1/spaces/{space}/finance-gst/invoices/{id}")
    public GstService.InvoiceGst invoice(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return gst.invoiceGst(access.resolve(request, space), id);
    }

    @PutMapping("/v1/spaces/{space}/finance-gst/invoices/{id}")
    public GstService.InvoiceGst calculateInvoice(@PathVariable UUID space, @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean preview,
            @RequestBody(required = false) GstService.InvoiceGstRequest body, HttpServletRequest request) {
        return gst.calculateInvoice(access.resolve(request, space), id, body == null ? new GstService.InvoiceGstRequest(null) : body, preview);
    }

    @GetMapping("/v1/spaces/{space}/finance-gst/expenses/{id}")
    public GstService.ExpenseGst expense(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return gst.expenseGst(access.resolve(request, space), id);
    }

    @PutMapping("/v1/spaces/{space}/finance-gst/expenses/{id}")
    public GstService.ExpenseGst recordExpense(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) GstService.ExpenseGstRequest body, HttpServletRequest request) {
        return gst.recordExpense(access.resolve(request, space), id, body == null ? new GstService.ExpenseGstRequest(null, null, null, null, null) : body);
    }
}
