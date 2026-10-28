package com.evpnc.hub.core.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.AuthorizeDecision;
import com.evpnc.hub.core.MutableClock;
import com.evpnc.hub.core.TestData;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GuardedPolicyTest {
    private final MutableClock clock = new MutableClock();

    @Test
    void aDecisionInsideTheBudgetIsPassedThrough() {
        GuardedPolicy guarded = new GuardedPolicy(request -> new AuthorizeDecision.Allowed());
        GuardedDecision result = guarded.decide(TestData.authorizeRequest(), TimeBudget.starting(Clock.systemUTC(), Duration.ofSeconds(2)));
        assertEquals(new GuardedDecision.Decided(new AuthorizeDecision.Allowed()), result);
    }

    @Test
    void aPolicyThatDoesNotAnswerIsAbandonedWhenTheBudgetEnds() {
        CountDownLatch never = new CountDownLatch(1);
        GuardedPolicy guarded = new GuardedPolicy(request -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AuthorizeDecision.Allowed();
        });
        long started = System.nanoTime();
        GuardedDecision result = guarded.decide(TestData.authorizeRequest(), TimeBudget.starting(Clock.systemUTC(), Duration.ofMillis(100)));
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        assertEquals(GuardedDecision.TimedOut.INSTANCE, result);
        assertTrue(tookMs < 1_000, "answered after " + tookMs + " ms, budget was 100 ms");
        never.countDown();
    }

    @Test
    void anExceptionInThePolicyIsReportedNotThrown() {
        GuardedPolicy guarded = new GuardedPolicy(request -> {
            throw new IllegalStateException("boom");
        });
        GuardedDecision result = guarded.decide(TestData.authorizeRequest(), TimeBudget.starting(Clock.systemUTC(), Duration.ofSeconds(2)));
        GuardedDecision.PolicyFailed failed = assertInstanceOf(GuardedDecision.PolicyFailed.class, result);
        assertEquals("boom", failed.cause().getMessage());
    }

    @Test
    void anAlreadyExpiredBudgetDoesNotCallThePolicy() {
        AtomicBoolean called = new AtomicBoolean(false);
        GuardedPolicy guarded = new GuardedPolicy(request -> {
            called.set(true);
            return new AuthorizeDecision.Allowed();
        });
        TimeBudget budget = TimeBudget.starting(clock, Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(2));
        assertEquals(GuardedDecision.TimedOut.INSTANCE, guarded.decide(TestData.authorizeRequest(), budget));
        assertFalse(called.get());
    }
}
