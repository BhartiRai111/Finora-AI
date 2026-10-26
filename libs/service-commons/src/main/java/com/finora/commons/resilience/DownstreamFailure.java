package com.finora.commons.resilience;

import com.finora.commons.error.ErrorCode;

/** A downstream call that did not produce a usable response. Maps to a safe gateway error; never carries upstream text. */
public class DownstreamFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind { TIMEOUT, UNAVAILABLE, CIRCUIT_OPEN, OVERLOADED, BAD_RESPONSE }

    private final Kind kind;

    public DownstreamFailure(Kind kind, String internalMessage, Throwable cause) {
        super(internalMessage, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public ErrorCode errorCode() {
        return switch (kind) {
            case TIMEOUT -> ErrorCode.UPSTREAM_TIMEOUT;
            case UNAVAILABLE, CIRCUIT_OPEN, OVERLOADED -> ErrorCode.UPSTREAM_UNAVAILABLE;
            case BAD_RESPONSE -> ErrorCode.UPSTREAM_ERROR;
        };
    }
}
