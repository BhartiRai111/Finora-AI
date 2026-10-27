package com.finora.finance.money;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Parses money from user input without ever touching floating point. */
public final class Amounts {

    private static final Pattern PLAIN = Pattern.compile("^-?\\d{1,15}(\\.\\d{1,4})?$");

    private Amounts() {
    }

    public enum Sign { POSITIVE, NON_ZERO, ANY }

    /**
     * @param field  name used in the error message
     * @param raw    decimal text such as "1250.50" (a JSON number is accepted as its exact text)
     * @throws ApiException 422 when malformed, out of range, or more precise than the currency allows
     */
    public static BigDecimal parse(String field, String raw, String currency, Sign sign) {
        int exponent = Currencies.exponent(currency)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "currency is not supported"));
        if (raw == null || !PLAIN.matcher(raw.trim()).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " must be a decimal number such as 1250.50");
        }
        BigDecimal value = new BigDecimal(raw.trim());
        if (value.scale() > exponent) {
            // Trailing zeros beyond the currency's precision are still extra precision; refuse rather than round.
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    field + " has too many decimals for " + currency + " (at most " + exponent + ")");
        }
        if (value.abs().compareTo(Currencies.MAX_AMOUNT) > 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " is too large");
        }
        switch (sign) {
            case POSITIVE -> {
                if (value.signum() <= 0) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " must be greater than zero");
                }
            }
            case NON_ZERO -> {
                if (value.signum() == 0) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " must not be zero");
                }
            }
            default -> {
            }
        }
        return value.setScale(exponent);
    }
}
