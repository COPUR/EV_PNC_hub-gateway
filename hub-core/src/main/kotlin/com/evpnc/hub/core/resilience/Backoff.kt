package com.evpnc.hub.core.resilience

import java.time.Duration
import kotlin.random.Random

/** When to try again. `null` means stop. [attempt] counts failures so far, starting at 1. */
fun interface Backoff {
    fun delayAfter(attempt: Int): Duration?
}

/** GIREVE guide: store and forward, for example after 5, 10 and 20 minutes, no bursts. */
class FixedSchedule(private val delays: List<Duration>) : Backoff {
    init {
        require(delays.isNotEmpty()) { "schedule must not be empty" }
    }

    override fun delayAfter(attempt: Int): Duration? = delays.getOrNull(attempt - 1)
}

/** Hubject AUP: on HTTP 429 back off exponentially with jitter. Jitter is full jitter on the upper half of the delay. */
class ExponentialBackoff(
    private val base: Duration,
    private val cap: Duration,
    private val maxAttempts: Int,
    private val random: Random = Random.Default,
) : Backoff {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(!base.isNegative && !base.isZero) { "base must be positive" }
    }

    override fun delayAfter(attempt: Int): Duration? {
        if (attempt > maxAttempts) return null
        val exponent = (attempt - 1).coerceAtMost(30)
        val raw = base.multipliedBy(1L shl exponent)
        val capped = if (raw > cap) cap else raw
        val half = capped.toMillis() / 2
        return Duration.ofMillis(half + random.nextLong(half + 1))
    }
}
