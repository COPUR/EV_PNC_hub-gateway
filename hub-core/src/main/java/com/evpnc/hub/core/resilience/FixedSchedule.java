package com.evpnc.hub.core.resilience;

import java.time.Duration;
import java.util.List;

/** GIREVE guide: store and forward, for example after 5, 10 and 20 minutes, no bursts. */
public final class FixedSchedule implements Backoff {
    private final List<Duration> delays;

    public FixedSchedule(List<Duration> delays) {
        if (delays.isEmpty()) {
            throw new IllegalArgumentException("schedule must not be empty");
        }
        this.delays = List.copyOf(delays);
    }

    @Override
    public Duration delayAfter(int attempt) {
        return attempt >= 1 && attempt <= delays.size() ? delays.get(attempt - 1) : null;
    }
}
