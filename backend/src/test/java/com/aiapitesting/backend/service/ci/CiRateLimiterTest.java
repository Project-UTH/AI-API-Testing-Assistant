package com.aiapitesting.backend.service.ci;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class CiRateLimiterTest {

    /** Đồng hồ giả chỉnh tay được. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-19T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void allowsUpToLimit_thenBlocks_perKey() {
        CiRateLimiter limiter = new CiRateLimiter(new MutableClock());

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("a", 3)).isTrue();
        }
        assertThat(limiter.tryAcquire("a", 3)).isFalse();
        assertThat(limiter.tryAcquire("b", 3)).isTrue(); // khoá khác không bị ảnh hưởng
    }

    @Test
    void windowResetsAfterOneMinute() {
        MutableClock clock = new MutableClock();
        CiRateLimiter limiter = new CiRateLimiter(clock);
        assertThat(limiter.tryAcquire("a", 1)).isTrue();
        assertThat(limiter.tryAcquire("a", 1)).isFalse();

        clock.advance(Duration.ofSeconds(61));

        assertThat(limiter.tryAcquire("a", 1)).isTrue();
    }
}
