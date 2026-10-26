package com.finora.commons.resilience;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** An outbound call. {@code headers} are sent as given; the caller is responsible for the allowlist. */
public record DownstreamRequest(String method, String pathAndQuery, Map<String, String> headers, byte[] body) {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");

    public DownstreamRequest {
        method = method.toUpperCase(Locale.ROOT);
        headers = Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    /**
     * Whether repeating the call cannot duplicate its effect: idempotent HTTP methods, or any method that carries an
     * {@code Idempotency-Key} (the owning service dedupes on it).
     */
    public boolean retryable() {
        if (SAFE_METHODS.contains(method)) {
            return true;
        }
        return headers.keySet().stream().anyMatch(h -> h.equalsIgnoreCase("Idempotency-Key"));
    }
}
