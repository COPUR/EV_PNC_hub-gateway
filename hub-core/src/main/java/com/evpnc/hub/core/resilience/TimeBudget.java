package com.evpnc.hub.core.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** A deadline measured on an injectable clock. The Hubject authorize answer must leave within 10 s (RH-HUBJECT-AUTH-004). */
public final class TimeBudget {
    private final Clock clock;
    private final Instant deadline;

    private TimeBudget(Clock clock, Instant deadline) {
        this.clock = clock;
        this.deadline = deadline;
    }

    public static TimeBudget starting(Clock clock, Duration total) {
        if (total.isNegative()) {
            throw new IllegalArgumentException("budget must not be negative");
        }
        return new TimeBudget(clock, clock.instant().plus(total));
    }

    public Instant deadline() {
        return deadline;
    }

    public Duration remaining() {
        Duration left = Duration.between(clock.instant(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    public boolean expired() {
        return remaining().isZero();
    }
}
