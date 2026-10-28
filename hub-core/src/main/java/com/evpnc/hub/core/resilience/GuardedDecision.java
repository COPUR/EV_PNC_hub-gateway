package com.evpnc.hub.core.resilience;

import com.evpnc.hub.core.AuthorizeDecision;

public sealed interface GuardedDecision {
    record Decided(AuthorizeDecision decision) implements GuardedDecision {}

    /** The policy did not answer inside the budget. The adapter picks the hub's fallback answer. */
    enum TimedOut implements GuardedDecision {
        INSTANCE
    }

    record PolicyFailed(Throwable cause) implements GuardedDecision {}
}
