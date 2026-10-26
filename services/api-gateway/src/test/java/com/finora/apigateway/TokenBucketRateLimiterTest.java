package com.finora.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, clock);

    @Test
    void allowsBurstThenRejectsWithRetryAfter() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("u", 3, 1.0).allowed()).isTrue();
        }
        TokenBucketRateLimiter.Decision denied = limiter.tryAcquire("u", 3, 1.0);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void refillsOverTime() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("u", 3, 1.0);
        }
        assertThat(limiter.tryAcquire("u", 3, 1.0).allowed()).isFalse();
        clock.advance(Duration.ofSeconds(2));
        assertThat(limiter.tryAcquire("u", 3, 1.0).allowed()).isTrue();
        assertThat(limiter.tryAcquire("u", 3, 1.0).allowed()).isTrue();
        assertThat(limiter.tryAcquire("u", 3, 1.0).allowed()).isFalse();
    }

    @Test
    void neverRefillsBeyondCapacity() {
        limiter.tryAcquire("u", 2, 1.0);
        clock.advance(Duration.ofHours(1));
        assertThat(limiter.tryAcquire("u", 2, 1.0).allowed()).isTrue();
        assertThat(limiter.tryAcquire("u", 2, 1.0).allowed()).isTrue();
        assertThat(limiter.tryAcquire("u", 2, 1.0).allowed()).isFalse();
    }

    @Test
    void subjectsAreIndependent() {
        assertThat(limiter.tryAcquire("a", 1, 0.001).allowed()).isTrue();
        assertThat(limiter.tryAcquire("a", 1, 0.001).allowed()).isFalse();
        assertThat(limiter.tryAcquire("b", 1, 0.001).allowed()).isTrue();
    }

    @Test
    void memoryIsBoundedUnderManySubjects() {
        TokenBucketRateLimiter small = new TokenBucketRateLimiter(10, clock);
        for (int i = 0; i < 1000; i++) {
            small.tryAcquire("attacker-" + i, 5, 1.0);
        }
        assertThat(small.trackedSubjects()).isLessThanOrEqualTo(11);
    }
}
