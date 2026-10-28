package com.evpnc.hub.core.resilience;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Token bucket per operation. Limits come from a hub profile ({@code rate_limits_per_minute}); the bucket holds one
 * minute of calls, so a burst up to the limit is allowed and the long-run rate cannot exceed it. Unknown operations are
 * not limited.
 */
public final class Throttler {
    private static final class Bucket {
        double tokens;
        long lastRefill;

        Bucket(double tokens, long lastRefill) {
            this.tokens = tokens;
            this.lastRefill = lastRefill;
        }
    }

    private final Map<String, Integer> limitsPerMinute;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new HashMap<>();

    public Throttler(Map<String, Integer> limitsPerMinute, Clock clock) {
        if (!limitsPerMinute.values().stream().allMatch(v -> v > 0)) {
            throw new IllegalArgumentException("limits must be positive");
        }
        this.limitsPerMinute = Map.copyOf(limitsPerMinute);
        this.clock = clock;
    }

    public synchronized boolean tryAcquire(String operation) {
        Integer limit = limitsPerMinute.get(operation);
        if (limit == null) {
            return true;
        }
        Bucket bucket = refill(operation, limit);
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** How long until {@code operation} has a token again. Zero when it can run now. */
    public synchronized Duration waitTime(String operation) {
        Integer limit = limitsPerMinute.get(operation);
        if (limit == null) {
            return Duration.ZERO;
        }
        Bucket bucket = refill(operation, limit);
        if (bucket.tokens >= 1.0) {
            return Duration.ZERO;
        }
        double missing = 1.0 - bucket.tokens;
        double millisPerToken = 60_000.0 / limit;
        return Duration.ofMillis((long) Math.ceil(missing * millisPerToken));
    }

    private Bucket refill(String operation, int limit) {
        long now = clock.millis();
        Bucket bucket = buckets.computeIfAbsent(operation, k -> new Bucket(limit, now));
        long elapsed = now - bucket.lastRefill;
        if (elapsed > 0) {
            bucket.tokens = Math.min(limit, bucket.tokens + elapsed * limit / 60_000.0);
            bucket.lastRefill = now;
        }
        return bucket;
    }
}
