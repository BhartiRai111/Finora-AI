package com.finora.commons.error;

import java.util.Locale;

/** Stable machine-readable error codes (Step 08 error catalogue). Titles are safe to show to users. */
public enum ErrorCode {
    BAD_REQUEST(400, "Bad request"),
    AUTH_REQUIRED(401, "Authentication required"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Not found"),
    METHOD_NOT_ALLOWED(405, "Method not allowed"),
    PAYLOAD_TOO_LARGE(413, "Payload too large"),
    UNSUPPORTED_MEDIA_TYPE(415, "Unsupported media type"),
    IDEMPOTENCY_IN_PROGRESS(409, "Idempotent request in progress"),
    VALIDATION_FAILED(422, "Validation failed"),
    IDEMPOTENCY_KEY_REUSED(422, "Idempotency key reused with a different request"),
    RATE_LIMITED(429, "Too many requests"),
    INTERNAL(500, "Internal error"),
    UPSTREAM_ERROR(502, "Upstream error"),
    UPSTREAM_UNAVAILABLE(503, "Upstream unavailable"),
    UPSTREAM_TIMEOUT(504, "Upstream timeout");

    private final int status;
    private final String title;

    ErrorCode(int status, String title) {
        this.status = status;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String slug() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static ErrorCode forStatus(int status) {
        return switch (status) {
            case 400, 406 -> BAD_REQUEST;
            case 401 -> AUTH_REQUIRED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> VALIDATION_FAILED;
            case 429 -> RATE_LIMITED;
            default -> status >= 500 ? INTERNAL : BAD_REQUEST;
        };
    }
}
