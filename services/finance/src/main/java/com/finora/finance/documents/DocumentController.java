package com.finora.finance.documents;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.core.Idem;
import com.finora.finance.insights.InsightsAccess;
import com.finora.finance.insights.InsightsContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DocumentController {

    private final InsightsAccess access;
    private final DocumentService documents;
    private final Idem idem;

    public DocumentController(InsightsAccess access, DocumentService documents, Idem idem) {
        this.access = access;
        this.documents = documents;
        this.idem = idem;
    }

    @GetMapping("/v1/spaces/{space}/finance-documents")
    public DocumentService.Page list(@PathVariable UUID space, @RequestParam(required = false) String status, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset, HttpServletRequest request) {
        return documents.list(access.resolve(request, space), status, limit, offset);
    }

    /** The file is the request body (application/octet-stream); kind and filename travel in the query string. */
    @PostMapping("/v1/spaces/{space}/finance-documents")
    public ResponseEntity<String> upload(@PathVariable UUID space, @RequestParam(required = false) String kind, @RequestParam String filename,
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        InsightsContext ctx = access.resolve(request, space);
        if (request.getContentLengthLong() > DocumentService.MAX_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The file is larger than " + (DocumentService.MAX_BYTES / 1024 / 1024) + " MB");
        }
        byte[] bytes = body == null ? new byte[0] : body;
        Object fingerprint;
        try {
            fingerprint = Map.of("kind", String.valueOf(kind), "filename", filename, "size", bytes.length,
                    "hash", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return idem.run(request, ctx.tenantId(), fingerprint, false, HttpStatus.CREATED, () -> documents.upload(ctx, kind, filename, bytes));
    }

    @GetMapping("/v1/spaces/{space}/finance-documents/{id}")
    public DocumentService.Doc get(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return documents.get(access.resolve(request, space), id);
    }

    /** Always a download (attachment, sniffed type, no sniffing by the browser); the file name is generated, never the uploaded one. */
    @GetMapping("/v1/spaces/{space}/finance-documents/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        DocumentService.Blob blob = documents.download(access.resolve(request, space), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(blob.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"document-" + id + "." + blob.extension() + "\"")
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store").body(blob.bytes());
    }

    @PostMapping("/v1/spaces/{space}/finance-documents/{id}/extract")
    public DocumentService.Doc extract(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return documents.extract(access.resolve(request, space), id);
    }

    @PostMapping("/v1/spaces/{space}/finance-documents/{id}/confirm")
    public ResponseEntity<String> confirm(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) DocumentService.ConfirmRequest body,
            HttpServletRequest request) {
        InsightsContext ctx = access.resolve(request, space);
        DocumentService.ConfirmRequest b = body == null ? new DocumentService.ConfirmRequest(null, null, null, null, null, null, null, null, null, null, null) : body;
        return idem.run(request, ctx.tenantId(), b, true, HttpStatus.OK, () -> documents.confirm(ctx, id, b));
    }

    @DeleteMapping("/v1/spaces/{space}/finance-documents/{id}")
    public DocumentService.Doc delete(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return documents.delete(access.resolve(request, space), id);
    }
}
