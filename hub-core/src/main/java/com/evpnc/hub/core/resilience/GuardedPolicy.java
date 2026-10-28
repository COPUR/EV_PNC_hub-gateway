package com.evpnc.hub.core.resilience;

import com.evpnc.hub.core.AuthorizationPolicy;
import com.evpnc.hub.core.AuthorizeDecision;
import com.evpnc.hub.core.AuthorizeRequest;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the policy hook under a {@link TimeBudget}. A hub that waits only N seconds must get an answer in N seconds,
 * whatever the policy engine does, so the call runs on its own thread and is abandoned when the budget ends.
 */
public final class GuardedPolicy {
    private final AuthorizationPolicy policy;
    private final ExecutorService executor;

    public GuardedPolicy(AuthorizationPolicy policy) {
        this(policy, Executors.newVirtualThreadPerTaskExecutor());
    }

    public GuardedPolicy(AuthorizationPolicy policy, ExecutorService executor) {
        this.policy = policy;
        this.executor = executor;
    }

    public GuardedDecision decide(AuthorizeRequest request, TimeBudget budget) {
        if (budget.expired()) {
            return GuardedDecision.TimedOut.INSTANCE;
        }
        Future<AuthorizeDecision> future = executor.submit(() -> policy.decide(request));
        try {
            return new GuardedDecision.Decided(future.get(budget.remaining().toNanos(), TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            return GuardedDecision.TimedOut.INSTANCE;
        } catch (ExecutionException e) {
            return new GuardedDecision.PolicyFailed(e.getCause() != null ? e.getCause() : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new GuardedDecision.PolicyFailed(e);
        }
    }
}
