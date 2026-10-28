package com.evpnc.hub.core.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.MutableClock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThrottlerTest {
    private final MutableClock clock = new MutableClock();

    @Test
    void aBurstUpToThePerMinuteLimitPassesTheNextCallIsRefused() {
        Throttler throttler = new Throttler(Map.of("PullEVSEStatus", 6), clock);
        for (int i = 1; i <= 6; i++) {
            assertTrue(throttler.tryAcquire("PullEVSEStatus"), "call " + i);
        }
        assertFalse(throttler.tryAcquire("PullEVSEStatus"));
    }

    @Test
    void tokensComeBackAtTheLimitRate() {
        Throttler throttler = new Throttler(Map.of("PullEVSEStatus", 6), clock);
        for (int i = 0; i < 6; i++) {
            throttler.tryAcquire("PullEVSEStatus");
        }
        assertEquals(Duration.ofSeconds(10), throttler.waitTime("PullEVSEStatus"));
        clock.advance(Duration.ofSeconds(10));
        assertTrue(throttler.tryAcquire("PullEVSEStatus"));
        assertFalse(throttler.tryAcquire("PullEVSEStatus"));
    }

    @Test
    void anIdleMinuteRefillsTheBucketButNeverAboveTheLimit() {
        Throttler throttler = new Throttler(Map.of("op", 2), clock);
        clock.advance(Duration.ofHours(1));
        assertTrue(throttler.tryAcquire("op"));
        assertTrue(throttler.tryAcquire("op"));
        assertFalse(throttler.tryAcquire("op"));
    }

    @Test
    void operationsWithoutALimitAreNotThrottledLimitsAreIndependent() {
        Throttler throttler = new Throttler(Map.of("a", 1), clock);
        assertTrue(throttler.tryAcquire("a"));
        assertFalse(throttler.tryAcquire("a"));
        for (int i = 0; i < 50; i++) {
            assertTrue(throttler.tryAcquire("unlisted"));
        }
        assertEquals(Duration.ZERO, throttler.waitTime("unlisted"));
    }
}
