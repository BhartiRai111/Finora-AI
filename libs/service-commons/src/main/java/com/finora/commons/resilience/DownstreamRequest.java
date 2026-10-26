package com.finora.commons.resilience;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** An outbound call. {@code headers} are sent as given; the caller is responsible for the allowlist. */
public record DownstreamRequest(String method, String pathAndQuery, Map<String, String> headers, byte[] body,
        boolean idempotentByContract) {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");

    public DownstreamRequest(String method, String pathAndQuery, Map<String, String> headers, byte[] body) {
        this(method, pathAndQuery, headers, body, false);
    }

    public DownstreamRequest {
        method = method.toUpperCase(Locale.ROOT);
        headers = Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    /**
     * Whether repeating the call cannot duplicate its effect: idempotent HTTP methods, or any method that carries an
     * {@code Idempotency-Key} (the owning service dedupes on it), or a call the sender declares side-effect free
     * ({@code idempotentByContract}, for example a read-only POST such as session validation).
     */
    public boolean retryable() {
        if (idempotentByContract || SAFE_METHODS.contains(method)) {
            return true;
        }
        return headers.keySet().stream().anyMatch(h -> h.equalsIgnoreCase("Idempotency-Key"));
    }
}
