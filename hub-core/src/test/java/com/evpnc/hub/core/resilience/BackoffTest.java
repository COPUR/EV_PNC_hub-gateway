package com.evpnc.hub.core.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BackoffTest {
    @Test
    void gireveStyleScheduleIsFollowedAndThenStops() {
        Backoff backoff = new FixedSchedule(List.of(Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(20)));
        assertEquals(Duration.ofMinutes(5), backoff.delayAfter(1));
        assertEquals(Duration.ofMinutes(10), backoff.delayAfter(2));
        assertEquals(Duration.ofMinutes(20), backoff.delayAfter(3));
        assertNull(backoff.delayAfter(4));
    }

    @Test
    void exponentialBackoffDoublesStaysInTheJitterBandCapsAndStops() {
        Backoff backoff = new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(8), 6, new Random(42));
        int[] ceilings = {1, 2, 4, 8, 8, 8};
        for (int i = 0; i < ceilings.length; i++) {
            long delay = backoff.delayAfter(i + 1).toMillis();
            assertTrue(delay >= ceilings[i] * 500L && delay <= ceilings[i] * 1000L,
                    "attempt " + (i + 1) + ": " + delay + " ms outside the band for " + ceilings[i] + "s");
        }
        assertNull(backoff.delayAfter(7));
    }
}
