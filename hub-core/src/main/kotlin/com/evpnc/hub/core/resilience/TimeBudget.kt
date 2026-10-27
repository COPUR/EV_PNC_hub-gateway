package com.evpnc.hub.core.resilience

import java.time.Clock
import java.time.Duration
import java.time.Instant

/** A deadline measured on an injectable clock. The Hubject authorize answer must leave within 10 s (RH-HUBJECT-AUTH-004). */
class TimeBudget private constructor(private val clock: Clock, val deadline: Instant) {
    fun remaining(): Duration {
        val left = Duration.between(clock.instant(), deadline)
        return if (left.isNegative) Duration.ZERO else left
    }

    fun expired(): Boolean = remaining().isZero

    companion object {
        fun starting(clock: Clock, total: Duration): TimeBudget {
            require(!total.isNegative) { "budget must not be negative" }
            return TimeBudget(clock, clock.instant().plus(total))
        }
    }
}
