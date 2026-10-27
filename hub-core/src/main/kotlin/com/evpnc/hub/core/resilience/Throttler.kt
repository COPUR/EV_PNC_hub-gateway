package com.evpnc.hub.core.resilience

import java.time.Clock
import java.time.Duration

/**
 * Token bucket per operation. Limits come from a hub profile (`rate_limits_per_minute`); the bucket holds one minute of
 * calls, so a burst up to the limit is allowed and the long-run rate cannot exceed it. Unknown operations are not limited.
 */
class Throttler(private val limitsPerMinute: Map<String, Int>, private val clock: Clock) {
    private class Bucket(var tokens: Double, var lastRefill: Long)

    private val buckets = HashMap<String, Bucket>()

    init {
        require(limitsPerMinute.values.all { it > 0 }) { "limits must be positive" }
    }

    @Synchronized
    fun tryAcquire(operation: String): Boolean {
        val limit = limitsPerMinute[operation] ?: return true
        val bucket = refill(operation, limit)
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            return true
        }
        return false
    }

    /** How long until [operation] has a token again. Zero when it can run now. */
    @Synchronized
    fun waitTime(operation: String): Duration {
        val limit = limitsPerMinute[operation] ?: return Duration.ZERO
        val bucket = refill(operation, limit)
        if (bucket.tokens >= 1.0) return Duration.ZERO
        val missing = 1.0 - bucket.tokens
        val millisPerToken = 60_000.0 / limit
        return Duration.ofMillis(Math.ceil(missing * millisPerToken).toLong())
    }

    private fun refill(operation: String, limit: Int): Bucket {
        val now = clock.millis()
        val bucket = buckets.getOrPut(operation) { Bucket(limit.toDouble(), now) }
        val elapsed = now - bucket.lastRefill
        if (elapsed > 0) {
            bucket.tokens = minOf(limit.toDouble(), bucket.tokens + elapsed * limit / 60_000.0)
            bucket.lastRefill = now
        }
        return bucket
    }
}
