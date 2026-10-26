package com.finora.commons.error;

import com.finora.commons.correlation.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Writes RFC 9457 problem details. Bodies never contain stack traces, hosts, SQL or upstream payloads. */
public class ProblemWriter {

    public static final String TYPE_BASE = "https://api.finora.example/problems/";
    public static final String CONTENT_TYPE = "application/problem+json";

    private final ObjectMapper mapper;

    public ProblemWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> body(ErrorCode code, String detail, String requestId, Integer retryAfterSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", TYPE_BASE + code.slug());
        body.put("title", code.title());
        body.put("status", code.status());
        body.put("code", code.name());
        if (detail != null) {
            body.put("detail", detail);
        }
        body.put("request_id", requestId);
        if (retryAfterSeconds != null) {
            body.put("retry_after_seconds", retryAfterSeconds);
        }
        return body;
    }

    public String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE);
        return attribute != null ? attribute.toString() : CorrelationId.generate();
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail,
            Integer retryAfterSeconds) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(code.status());
        response.setContentType(CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        if (retryAfterSeconds != null) {
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        }
        response.getOutputStream().write(mapper.writeValueAsBytes(body(code, detail, requestId(request), retryAfterSeconds)));
    }
}
