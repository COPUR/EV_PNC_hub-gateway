package com.evpnc.hub.core.resilience;

import java.time.Duration;
import java.util.random.RandomGenerator;

/** Hubject AUP: on HTTP 429 back off exponentially with jitter. Jitter is full jitter on the upper half of the delay. */
public final class ExponentialBackoff implements Backoff {
    private final Duration base;
    private final Duration cap;
    private final int maxAttempts;
    private final RandomGenerator random;

    public ExponentialBackoff(Duration base, Duration cap, int maxAttempts) {
        this(base, cap, maxAttempts, RandomGenerator.getDefault());
    }

    public ExponentialBackoff(Duration base, Duration cap, int maxAttempts, RandomGenerator random) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (base.isNegative() || base.isZero()) {
            throw new IllegalArgumentException("base must be positive");
        }
        this.base = base;
        this.cap = cap;
        this.maxAttempts = maxAttempts;
        this.random = random;
    }

    @Override
    public Duration delayAfter(int attempt) {
        if (attempt > maxAttempts) {
            return null;
        }
        int exponent = Math.min(attempt - 1, 30);
        Duration raw = base.multipliedBy(1L << exponent);
        Duration capped = raw.compareTo(cap) > 0 ? cap : raw;
        long half = capped.toMillis() / 2;
        return Duration.ofMillis(half + random.nextLong(half + 1));
    }
}
