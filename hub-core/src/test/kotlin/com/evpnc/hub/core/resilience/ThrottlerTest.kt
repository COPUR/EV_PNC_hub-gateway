package com.evpnc.hub.core.resilience

import com.evpnc.hub.core.MutableClock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThrottlerTest {
    private val clock = MutableClock()

    @Test
    fun `a burst up to the per-minute limit passes, the next call is refused`() {
        val throttler = Throttler(mapOf("PullEVSEStatus" to 6), clock)
        repeat(6) { assertTrue(throttler.tryAcquire("PullEVSEStatus"), "call ${it + 1}") }
        assertFalse(throttler.tryAcquire("PullEVSEStatus"))
    }

    @Test
    fun `tokens come back at the limit rate`() {
        val throttler = Throttler(mapOf("PullEVSEStatus" to 6), clock)
        repeat(6) { throttler.tryAcquire("PullEVSEStatus") }
        assertEquals(Duration.ofSeconds(10), throttler.waitTime("PullEVSEStatus"))
        clock.advance(Duration.ofSeconds(10))
        assertTrue(throttler.tryAcquire("PullEVSEStatus"))
        assertFalse(throttler.tryAcquire("PullEVSEStatus"))
    }

    @Test
    fun `an idle minute refills the bucket but never above the limit`() {
        val throttler = Throttler(mapOf("op" to 2), clock)
        clock.advance(Duration.ofHours(1))
        assertTrue(throttler.tryAcquire("op"))
        assertTrue(throttler.tryAcquire("op"))
        assertFalse(throttler.tryAcquire("op"))
    }

    @Test
    fun `operations without a limit are not throttled, limits are independent`() {
        val throttler = Throttler(mapOf("a" to 1), clock)
        assertTrue(throttler.tryAcquire("a"))
        assertFalse(throttler.tryAcquire("a"))
        repeat(50) { assertTrue(throttler.tryAcquire("unlisted")) }
        assertEquals(Duration.ZERO, throttler.waitTime("unlisted"))
    }
}
