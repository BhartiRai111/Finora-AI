package com.finora.finance.accounting;

import java.util.regex.Pattern;

/** Makes a provider error text safe to store and show: tokens, bearer headers, secrets and long opaque strings are removed; the length is bounded. */
public final class Redaction {

    private static final Pattern SECRETISH = Pattern.compile("(?i)(authorization[\"'=:\\s]+(bearer\\s+)?\\S+|bearer\\s+[A-Za-z0-9._~+/=-]+|(access|refresh|id)?[_-]?token[\"'=:\\s]+[A-Za-z0-9._~+/=-]{6,}|(client[_-]?secret|api[_-]?key|password)[\"'=:\\s]+\\S+|[A-Za-z0-9+/_=-]{32,})");

    private Redaction() {
    }

    public static String safe(String message) {
        if (message == null) {
            return null;
        }
        String s = SECRETISH.matcher(message).replaceAll("[redacted]").replaceAll("[\\p{Cntrl}]+", " ").trim();
        return s.length() > 300 ? s.substring(0, 299) + "…" : s;
    }
}
