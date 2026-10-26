package com.finora.commons.resilience;

import java.util.List;
import java.util.Map;

/** A completed downstream exchange with a status below 500, or a 5xx the caller chose to inspect. */
public record DownstreamResponse(int status, Map<String, List<String>> headers, byte[] body) {
}
