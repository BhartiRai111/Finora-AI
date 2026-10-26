package com.finora.commons.resilience;

import java.time.Duration;

/**
 * Timeouts, retry, circuit-breaker and bulkhead settings for one downstream. Numeric defaults are engineering
 * starting points (DEC-50: final values are an owner/performance decision) and are all configurable.
 */
public record DownstreamPolicy(
        Duration connectTimeout,
        Duration attemptTimeout,
        Duration totalDeadline,
        int maxRetries,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration backoffJitter,
        int circuitWindow,
        int circuitMinCalls,
        float circuitFailureRatePercent,
        Duration circuitOpenWait,
        int maxConcurrentCalls) {

    public static DownstreamPolicy defaults() {
        return new DownstreamPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofSeconds(8),
                2,
                Duration.ofMillis(100),
                Duration.ofSeconds(1),
                Duration.ofMillis(50),
                20,
                10,
                50f,
                Duration.ofSeconds(10),
                50);
    }

    public DownstreamPolicy {
        if (maxRetries < 0 || maxConcurrentCalls < 1 || circuitWindow < 1 || circuitMinCalls < 1) {
            throw new IllegalArgumentException("invalid downstream policy");
        }
        if (attemptTimeout.compareTo(totalDeadline) > 0) {
            throw new IllegalArgumentException("attemptTimeout must not exceed totalDeadline");
        }
    }
}
