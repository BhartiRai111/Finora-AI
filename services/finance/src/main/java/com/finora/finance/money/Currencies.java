package com.finora.finance.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Supported currencies and their ISO 4217 minor-unit exponents. The list is an engineering default (PD-09 is pending) and
 * is deliberately explicit: an unlisted currency is refused rather than guessed. No exchange rates exist anywhere in this
 * service; amounts are never converted.
 */
public final class Currencies {

    private static final Map<String, Integer> EXPONENT = Map.ofEntries(
            Map.entry("INR", 2), Map.entry("USD", 2), Map.entry("EUR", 2), Map.entry("GBP", 2), Map.entry("AED", 2),
            Map.entry("SGD", 2), Map.entry("AUD", 2), Map.entry("CAD", 2), Map.entry("CHF", 2), Map.entry("NZD", 2),
            Map.entry("HKD", 2), Map.entry("SAR", 2), Map.entry("ZAR", 2), Map.entry("JPY", 0), Map.entry("KRW", 0),
            Map.entry("KWD", 3), Map.entry("BHD", 3), Map.entry("OMR", 3));

    /** Largest amount accepted in one field (1 trillion minus one minor unit at 4 digits). */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.9999");

    private Currencies() {
    }

    public static Optional<Integer> exponent(String currency) {
        return Optional.ofNullable(currency == null ? null : EXPONENT.get(currency));
    }

    public static boolean supported(String currency) {
        return exponent(currency).isPresent();
    }

    public static java.util.Set<String> codes() {
        return new java.util.TreeSet<>(EXPONENT.keySet());
    }

    /** The exponent of a currency already known to be valid (stored data). */
    public static int exponentOrThrow(String currency) {
        return exponent(currency).orElseThrow(() -> new IllegalStateException("Unsupported currency in stored data: " + currency));
    }

    /** Formats an amount that is already valid for its currency, always with exactly the currency's decimals. */
    public static String format(BigDecimal amount, String currency) {
        if (amount == null) {
            return null;
        }
        return amount.setScale(exponentOrThrow(currency), RoundingMode.UNNECESSARY).toPlainString();
    }
}
