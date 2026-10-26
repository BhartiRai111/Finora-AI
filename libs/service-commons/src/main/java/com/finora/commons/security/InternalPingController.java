package com.finora.commons.security;

import com.finora.commons.correlation.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Communication check for operators and tests: proves that a caller's internal token is accepted by this service.
 * Returns only what the caller already knows (its own kind) plus this service's name. Not a business endpoint.
 */
@RestController
public class InternalPingController {

    private final InternalTokenVerifier verifier;

    public InternalPingController(InternalTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @GetMapping("/internal/v1/ping")
    public Map<String, Object> ping(HttpServletRequest request) {
        InternalPrincipal principal = (InternalPrincipal) request.getAttribute(InternalPrincipal.REQUEST_ATTRIBUTE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", verifier.audience());
        body.put("principalKind", principal.kind().name());
        body.put("requestId", CorrelationId.current());
        return body;
    }
}
