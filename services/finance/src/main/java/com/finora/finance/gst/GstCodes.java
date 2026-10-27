package com.finora.finance.gst;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Structural GST facts used for validation only. State and union-territory codes are the two-digit codes that start every GSTIN, collected from
 * public GST code lists (a qualified professional must still validate them before any reliance). The GSTIN check character is the
 * Luhn mod-36 scheme described by the python-stdnum library and cross-checked against published sample numbers in the tests. A passing check
 * means a string is well formed; it does NOT mean the GSTIN is registered or active, and nothing here contacts the GST portal.
 */
public final class GstCodes {

    public static final String RULE_VERSION = "FINORA-GST-PREP-1";
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Pattern FORMAT = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");

    private static final Map<String, String> STATES = new TreeMap<>(Map.ofEntries(
            Map.entry("01", "Jammu and Kashmir"), Map.entry("02", "Himachal Pradesh"), Map.entry("03", "Punjab"), Map.entry("04", "Chandigarh"), Map.entry("05", "Uttarakhand"),
            Map.entry("06", "Haryana"), Map.entry("07", "Delhi"), Map.entry("08", "Rajasthan"), Map.entry("09", "Uttar Pradesh"), Map.entry("10", "Bihar"), Map.entry("11", "Sikkim"),
            Map.entry("12", "Arunachal Pradesh"), Map.entry("13", "Nagaland"), Map.entry("14", "Manipur"), Map.entry("15", "Mizoram"), Map.entry("16", "Tripura"),
            Map.entry("17", "Meghalaya"), Map.entry("18", "Assam"), Map.entry("19", "West Bengal"), Map.entry("20", "Jharkhand"), Map.entry("21", "Odisha"),
            Map.entry("22", "Chhattisgarh"), Map.entry("23", "Madhya Pradesh"), Map.entry("24", "Gujarat"), Map.entry("25", "Daman and Diu (before 26 January 2020)"),
            Map.entry("26", "Dadra and Nagar Haveli and Daman and Diu"), Map.entry("27", "Maharashtra"), Map.entry("28", "Andhra Pradesh (before reorganisation)"),
            Map.entry("29", "Karnataka"), Map.entry("30", "Goa"), Map.entry("31", "Lakshadweep"), Map.entry("32", "Kerala"), Map.entry("33", "Tamil Nadu"),
            Map.entry("34", "Puducherry"), Map.entry("35", "Andaman and Nicobar Islands"), Map.entry("36", "Telangana"), Map.entry("37", "Andhra Pradesh"), Map.entry("38", "Ladakh"),
            Map.entry("97", "Other Territory"), Map.entry("99", "Centre Jurisdiction")));

    /**
     * Union territories without a legislature, and codes that are not a place of supply. For these, an intra-state supply may need UTGST instead of
     * SGST (or special handling), which this product does NOT implement: such a calculation is refused rather than guessed.
     */
    private static final Set<String> UNSUPPORTED_INTRA = Set.of("04", "25", "26", "31", "35", "38", "97", "99");
    private static final Set<String> NOT_A_PLACE = Set.of("97", "99");

    private GstCodes() {
    }

    public static Map<String, String> states() {
        return STATES;
    }

    public static boolean knownState(String code) {
        return code != null && STATES.containsKey(code);
    }

    public static boolean usablePlaceOfSupply(String code) {
        return knownState(code) && !NOT_A_PLACE.contains(code);
    }

    public static boolean intraStateUnsupported(String code) {
        return UNSUPPORTED_INTRA.contains(code);
    }

    public static char checkCharacter(String first14) {
        int sum = 0;
        for (int i = 0; i < first14.length(); i++) {
            int v = ALPHABET.indexOf(first14.charAt(first14.length() - 1 - i));
            int p = v * (i % 2 == 0 ? 2 : 1);
            sum += p / 36 + p % 36;
        }
        return ALPHABET.charAt((36 - sum % 36) % 36);
    }

    /** Null when the GSTIN is well formed, has a known state code and a matching check character; otherwise a short reason. */
    public static String problem(String gstin) {
        if (gstin == null || !FORMAT.matcher(gstin).matches()) {
            return "GSTIN must be 15 characters in the GSTIN format";
        }
        if (!knownState(gstin.substring(0, 2))) {
            return "GSTIN starts with an unknown state code";
        }
        if (checkCharacter(gstin.substring(0, 14)) != gstin.charAt(14)) {
            return "GSTIN check character does not match (possible typing error)";
        }
        return null;
    }

    public static Optional<String> stateOf(String gstin) {
        return gstin != null && gstin.length() == 15 ? Optional.of(gstin.substring(0, 2)) : Optional.empty();
    }

    public static String mask(String gstin) {
        return gstin == null ? null : gstin.substring(0, 2) + "*".repeat(10) + gstin.substring(12);
    }
}
