package com.evpnc.hub.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand. */
public final class MutableClock extends Clock {
    private Instant now;

    public MutableClock() {
        this(Instant.parse("2026-11-02T09:00:00Z"));
    }

    public MutableClock(Instant now) {
        this.now = now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    public void advance(Duration by) {
        now = now.plus(by);
    }
}
