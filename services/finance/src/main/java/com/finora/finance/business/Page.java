package com.finora.finance.business;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import java.util.List;

/** One page of a list plus the unpaged total. Lists use a stable order (date desc, id) and limit/offset. */
public record Page<T>(List<T> items, long total, int limit, int offset) {

    public static final int MAX_LIMIT = 100;

    public static int limit(Integer raw) {
        int v = raw == null ? 25 : raw;
        if (v < 1 || v > MAX_LIMIT) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_LIMIT);
        }
        return v;
    }

    public static int offset(Integer raw) {
        int v = raw == null ? 0 : raw;
        if (v < 0 || v > 100_000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "offset must be between 0 and 100000");
        }
        return v;
    }
}
