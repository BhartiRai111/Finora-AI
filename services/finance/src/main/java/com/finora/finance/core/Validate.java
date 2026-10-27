package com.finora.finance.core;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Small helpers that turn bad input into field-level 422 messages (never echoing the input). */
public final class Validate {

    private Validate() {
    }

    public static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }

    public static String text(String field, String value, int min, int max, boolean required) {
        String v = value == null ? null : value.trim();
        if (v == null || v.isEmpty()) {
            if (required) {
                throw invalid(field + " is required");
            }
            return null;
        }
        if (v.length() < min || v.length() > max || v.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw invalid(field + " must be " + min + "-" + max + " characters without control characters");
        }
        return v;
    }

    public static LocalDate date(String field, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw invalid(field + " is required");
            }
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw invalid(field + " must be a date like 2026-03-31");
        }
    }

    public static <E extends Enum<E>> E choice(String field, String value, Class<E> type) {
        if (value == null) {
            throw invalid(field + " is required");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid(field + " is not a valid choice");
        }
    }

    public static UUID uuid(String field, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw invalid(field + " is required");
            }
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw invalid(field + " is not a valid id");
        }
    }

    public static List<String> tags(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String t : raw) {
            String n = t == null ? "" : t.trim().toLowerCase(Locale.ROOT);
            if (n.isEmpty() || n.length() > 30 || !n.matches("[\\p{L}\\p{N} _:-]+")) {
                throw invalid("each tag must be 1-30 letters, digits, spaces, '-', '_' or ':'");
            }
            if (!out.contains(n)) {
                out.add(n);
            }
        }
        if (out.size() > 10) {
            throw invalid("at most 10 tags per transaction");
        }
        return out;
    }

    public static int version(Integer version) {
        if (version == null || version < 0) {
            throw invalid("version is required");
        }
        return version;
    }
}
