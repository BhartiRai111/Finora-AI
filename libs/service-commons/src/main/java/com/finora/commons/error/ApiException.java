package com.finora.commons.error;

/** A failure that is safe to describe to the caller: the code and detail are shown, nothing else is. */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final Integer retryAfterSeconds;

    public ApiException(ErrorCode code, String safeDetail) {
        this(code, safeDetail, null);
    }

    public ApiException(ErrorCode code, String safeDetail, Integer retryAfterSeconds) {
        super(safeDetail);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ErrorCode code() {
        return code;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
