package com.finora.apigateway;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token buckets, one per subject key. Single-replica only (approved scope): with several gateway replicas
 * each would enforce its own limit. Memory is bounded: when the table is full, idle full buckets are dropped first and,
 * if still full, new subjects share the overflow bucket so the limiter never grows without bound.
 */
public class TokenBucketRateLimiter {

    public record Decision(boolean allowed, int retryAfterSeconds) {
    }

    private static final class Bucket {
        double tokens;
        long lastNanos;

        Bucket(double tokens, long lastNanos) {
            this.tokens = tokens;
            this.lastNanos = lastNanos;
        }
    }

    private static final String OVERFLOW_KEY = "overflow";

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int maxSubjects;
    private final Clock clock;

    public TokenBucketRateLimiter(int maxSubjects, Clock clock) {
        this.maxSubjects = maxSubjects;
        this.clock = clock;
    }

    public Decision tryAcquire(String key, int capacity, double refillPerSecond) {
        long now = nanos();
        if (buckets.size() >= maxSubjects && !buckets.containsKey(key)) {
            evictIdle(now, capacity, refillPerSecond);
            if (buckets.size() >= maxSubjects) {
                key = OVERFLOW_KEY;
            }
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (bucket) {
            double elapsedSeconds = Math.max(0, now - bucket.lastNanos) / 1_000_000_000.0;
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * refillPerSecond);
            bucket.lastNanos = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return new Decision(true, 0);
            }
            int wait = (int) Math.ceil((1.0 - bucket.tokens) / refillPerSecond);
            return new Decision(false, Math.max(1, wait));
        }
    }

    public int trackedSubjects() {
        return buckets.size();
    }

    private void evictIdle(long now, int capacity, double refillPerSecond) {
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Bucket b = it.next().getValue();
            synchronized (b) {
                double refilled = b.tokens + Math.max(0, now - b.lastNanos) / 1_000_000_000.0 * refillPerSecond;
                if (refilled >= capacity) {
                    it.remove();
                }
            }
        }
    }

    private long nanos() {
        return clock.instant().getEpochSecond() * 1_000_000_000L + clock.instant().getNano();
    }
}
