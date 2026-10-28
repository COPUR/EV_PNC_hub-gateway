package com.evpnc.hub.core.resilience;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryIdempotencyStore implements IdempotencyStore {
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    @Override
    public boolean firstTime(String key) {
        return seen.add(key);
    }
}
