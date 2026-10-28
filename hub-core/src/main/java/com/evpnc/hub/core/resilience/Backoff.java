package com.evpnc.hub.core.resilience;

import java.time.Duration;

/** When to try again. {@code null} means stop. {@code attempt} counts failures so far, starting at 1. */
@FunctionalInterface
public interface Backoff {
    Duration delayAfter(int attempt);
}
