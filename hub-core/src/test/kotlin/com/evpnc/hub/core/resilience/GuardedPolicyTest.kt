package com.evpnc.hub.core.resilience

import com.evpnc.hub.core.AuthorizationPolicy
import com.evpnc.hub.core.AuthorizeDecision
import com.evpnc.hub.core.MutableClock
import com.evpnc.hub.core.authorizeRequest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GuardedPolicyTest {
    private val clock = MutableClock()

    @Test
    fun `a decision inside the budget is passed through`() {
        val guarded = GuardedPolicy(AuthorizationPolicy { AuthorizeDecision.Allowed() })
        val result = guarded.decide(authorizeRequest(), TimeBudget.starting(java.time.Clock.systemUTC(), Duration.ofSeconds(2)))
        assertEquals(GuardedDecision.Decided(AuthorizeDecision.Allowed()), result)
    }

    @Test
    fun `a policy that does not answer is abandoned when the budget ends`() {
        val never = CountDownLatch(1)
        val guarded = GuardedPolicy(AuthorizationPolicy { never.await(); AuthorizeDecision.Allowed() })
        val started = System.nanoTime()
        val result = guarded.decide(authorizeRequest(), TimeBudget.starting(java.time.Clock.systemUTC(), Duration.ofMillis(100)))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(GuardedDecision.TimedOut, result)
        assertTrue(tookMs < 1_000, "answered after ${tookMs} ms, budget was 100 ms")
        never.countDown()
    }

    @Test
    fun `an exception in the policy is reported, not thrown`() {
        val guarded = GuardedPolicy(AuthorizationPolicy { error("boom") })
        val result = guarded.decide(authorizeRequest(), TimeBudget.starting(java.time.Clock.systemUTC(), Duration.ofSeconds(2)))
        assertIs<GuardedDecision.PolicyFailed>(result)
        assertEquals("boom", result.cause.message)
    }

    @Test
    fun `an already expired budget does not call the policy`() {
        var called = false
        val guarded = GuardedPolicy(AuthorizationPolicy { called = true; AuthorizeDecision.Allowed() })
        val budget = TimeBudget.starting(clock, Duration.ofSeconds(1))
        clock.advance(Duration.ofSeconds(2))
        assertEquals(GuardedDecision.TimedOut, guarded.decide(authorizeRequest(), budget))
        assertEquals(false, called)
    }
}
