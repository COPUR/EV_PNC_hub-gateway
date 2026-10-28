package com.evpnc.hub.core.resilience;

@FunctionalInterface
public interface IdempotencyStore {
    /** @return true when {@code key} was not seen before. */
    boolean firstTime(String key);
}
