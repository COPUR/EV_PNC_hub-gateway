package com.evpnc.hub.core.resilience

import java.time.Duration
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackoffTest {
    @Test
    fun `GIREVE style schedule is followed and then stops`() {
        val backoff = FixedSchedule(listOf(5, 10, 20).map { Duration.ofMinutes(it.toLong()) })
        assertEquals(Duration.ofMinutes(5), backoff.delayAfter(1))
        assertEquals(Duration.ofMinutes(10), backoff.delayAfter(2))
        assertEquals(Duration.ofMinutes(20), backoff.delayAfter(3))
        assertNull(backoff.delayAfter(4))
    }

    @Test
    fun `exponential backoff doubles, stays in the jitter band, caps and stops`() {
        val backoff = ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(8), maxAttempts = 6, random = Random(42))
        val ceilings = listOf(1, 2, 4, 8, 8, 8)
        ceilings.forEachIndexed { i, ceiling ->
            val delay = backoff.delayAfter(i + 1)!!.toMillis()
            assertTrue(delay in (ceiling * 500L)..(ceiling * 1000L), "attempt ${i + 1}: $delay ms outside the band for ${ceiling}s")
        }
        assertNull(backoff.delayAfter(7))
    }
}
