package com.finora.finance.assistant;

/** A provider failure, classified so the caller can tell a bad key from a slow service from a bad answer. The message never holds secrets or prompts. */
public class AiProviderException extends Exception {

    public enum Kind { AUTH_FAILED, RATE_LIMITED, TIMEOUT, UNAVAILABLE, MALFORMED_RESPONSE, SERVICE_ERROR }

    private static final long serialVersionUID = 1L;

    private final Kind kind;

    public AiProviderException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** Only temporary conditions are retried; a rejected key or a malformed answer is not. */
    public boolean retryable() {
        return kind == Kind.TIMEOUT || kind == Kind.UNAVAILABLE;
    }
}
