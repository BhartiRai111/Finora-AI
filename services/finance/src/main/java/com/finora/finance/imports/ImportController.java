package com.finora.finance.imports;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.core.Idem;
import com.finora.finance.insights.InsightsAccess;
import com.finora.finance.insights.InsightsContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ImportController {

    private final InsightsAccess access;
    private final ImportService imports;
    private final Idem idem;

    public ImportController(InsightsAccess access, ImportService imports, Idem idem) {
        this.access = access;
        this.imports = imports;
        this.idem = idem;
    }

    @GetMapping("/v1/spaces/{space}/finance-imports")
    public ImportService.Page<ImportService.Job> list(@PathVariable UUID space, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset,
            HttpServletRequest request) {
        return imports.list(access.resolve(request, space), limit, offset);
    }

    /** The file is the request body (text/csv or application/octet-stream); the details travel in the query string. */
    @PostMapping("/v1/spaces/{space}/finance-imports")
    public ResponseEntity<String> upload(@PathVariable UUID space, @RequestParam String kind, @RequestParam String filename,
            @RequestParam(name = "account_id", required = false) UUID accountId, @RequestParam(required = false) String currency,
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        InsightsContext ctx = access.resolve(request, space);
        if (request.getContentLengthLong() > CsvParser.MAX_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The file is larger than " + (CsvParser.MAX_BYTES / 1024 / 1024) + " MB");
        }
        byte[] bytes = body == null ? new byte[0] : body;
        Object fingerprint = Map.of("kind", kind, "filename", filename, "account", String.valueOf(accountId), "currency", String.valueOf(currency), "size", bytes.length,
                "hash", java.util.HexFormat.of().formatHex(sha(bytes)));
        return idem.run(request, ctx.tenantId(), fingerprint, false, HttpStatus.CREATED, () -> imports.upload(ctx, kind, filename, accountId, currency, bytes));
    }

    private static byte[] sha(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @GetMapping("/v1/spaces/{space}/finance-imports/{id}")
    public ImportService.Job get(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return imports.get(access.resolve(request, space), id);
    }

    @PutMapping("/v1/spaces/{space}/finance-imports/{id}/mapping")
    public ImportService.Job map(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) ImportService.MappingRequest body, HttpServletRequest request) {
        return imports.map(access.resolve(request, space), id, body == null ? new ImportService.MappingRequest(null, null) : body);
    }

    @GetMapping("/v1/spaces/{space}/finance-imports/{id}/rows")
    public ImportService.Page<ImportService.RowView> rows(@PathVariable UUID space, @PathVariable UUID id, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset, HttpServletRequest request) {
        return imports.rows(access.resolve(request, space), id, status, limit, offset);
    }

    @GetMapping("/v1/spaces/{space}/finance-imports/{id}/error-report")
    public ResponseEntity<byte[]> errorReport(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        String csv = imports.errorReport(access.resolve(request, space), id);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-" + id + "-errors.csv\"").header("X-Content-Type-Options", "nosniff")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Starting the import needs no Idempotency-Key: the job state machine makes it exactly-once (one conditional claim, a ledger entry per
     * row in the same transaction as the record), and a retry of a finished or running commit just reports the job.
     */
    @PostMapping("/v1/spaces/{space}/finance-imports/{id}/commit")
    public ImportService.Job commit(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) ImportService.CommitRequest body, HttpServletRequest request) {
        return imports.commit(access.resolve(request, space), id, body == null ? new ImportService.CommitRequest(null, null) : body);
    }

    @PostMapping("/v1/spaces/{space}/finance-imports/{id}/cancel")
    public ImportService.Job cancel(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return imports.cancel(access.resolve(request, space), id);
    }
}
