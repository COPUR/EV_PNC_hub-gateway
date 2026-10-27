package com.evpnc.hub.core.resilience

import com.evpnc.hub.core.AuthorizationPolicy
import com.evpnc.hub.core.AuthorizeDecision
import com.evpnc.hub.core.AuthorizeRequest
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

sealed interface GuardedDecision {
    data class Decided(val decision: AuthorizeDecision) : GuardedDecision

    /** The policy did not answer inside the budget. The adapter picks the hub's fallback answer. */
    data object TimedOut : GuardedDecision

    data class PolicyFailed(val cause: Throwable) : GuardedDecision
}

/**
 * Runs the policy hook under a [TimeBudget]. A hub that waits only N seconds must get an answer in N seconds,
 * whatever the policy engine does, so the call runs on its own thread and is abandoned when the budget ends.
 */
class GuardedPolicy(
    private val policy: AuthorizationPolicy,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) {
    fun decide(request: AuthorizeRequest, budget: TimeBudget): GuardedDecision {
        if (budget.expired()) return GuardedDecision.TimedOut
        val future = executor.submit<AuthorizeDecision> { policy.decide(request) }
        return try {
            GuardedDecision.Decided(future.get(budget.remaining().toNanos(), TimeUnit.NANOSECONDS))
        } catch (_: TimeoutException) {
            future.cancel(true)
            GuardedDecision.TimedOut
        } catch (e: ExecutionException) {
            GuardedDecision.PolicyFailed(e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            GuardedDecision.PolicyFailed(e)
        }
    }
}
