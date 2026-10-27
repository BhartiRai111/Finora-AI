package com.finora.finance.core;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.commons.idempotency.IdempotencyService;
import com.finora.commons.idempotency.IdempotencyService.StoredResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes retried writes safe. The idempotency key row is stored in the same database transaction as the work, so a retry
 * (or a double click, or a network replay) returns the first response and never executes twice; the same key with a
 * different request is refused (422). The scope is the tenant, so keys cannot collide across users.
 */
@Component
public class Idem {

    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public Idem(IdempotencyService idempotency, ObjectMapper mapper) {
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    public ResponseEntity<String> run(HttpServletRequest request, FinanceContext ctx, Object requestBody, boolean required,
            HttpStatus status, Supplier<?> work) {
        String key = request.getHeader("Idempotency-Key");
        AtomicBoolean executed = new AtomicBoolean(false);
        Supplier<StoredResponse> producer = () -> {
            executed.set(true);
            return new StoredResponse(status.value(), mapper.writeValueAsString(work.get()));
        };
        StoredResponse response;
        if (key == null) {
            if (required) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "The Idempotency-Key header is required for this request");
            }
            response = producer.get();
        } else {
            String scope = ctx.tenantId() + ":" + request.getMethod() + ":" + request.getRequestURI();
            String hash = IdempotencyService.hashRequest(request.getMethod(), request.getRequestURI(),
                    (requestBody == null ? "" : mapper.writeValueAsString(requestBody)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            response = idempotency.execute(scope, key, hash, producer);
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON);
        if (key != null && !executed.get()) {
            builder.header("Idempotency-Replayed", "true");
        }
        return builder.body(response.body());
    }
}
