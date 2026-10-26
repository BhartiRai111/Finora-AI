package com.finora.commons.security;

/** Why an internal token was rejected. The reason is logged, never returned to the caller. */
public class InternalAuthException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        MALFORMED, UNSUPPORTED_ALGORITHM, UNTRUSTED_ISSUER, BAD_SIGNATURE, WRONG_AUDIENCE, EXPIRED,
        NOT_YET_VALID, TTL_TOO_LONG, MISSING_CLAIM, INVALID_CLAIM
    }

    private final Reason reason;

    public InternalAuthException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
