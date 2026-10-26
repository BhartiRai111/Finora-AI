package com.finora.commons.correlation;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/** Request correlation: a canonical UUID carried in {@code X-Request-Id} across every hop. */
public final class CorrelationId {

    public static final String HEADER = "X-Request-Id";
    public static final String TRACEPARENT = "traceparent";
    public static final String REQUEST_ATTRIBUTE = CorrelationId.class.getName() + ".value";
    public static final String MDC_KEY = "requestId";

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private CorrelationId() {
    }

    public static boolean isValid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }

    public static boolean isValidTraceparent(String value) {
        return value != null && TRACEPARENT_PATTERN.matcher(value).matches();
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /** The id of the request being handled on this thread, or null outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
