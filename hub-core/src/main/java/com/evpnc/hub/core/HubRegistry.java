package com.evpnc.hub.core;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class HubRegistry {
    private final Map<Hub, HubAdapter> byHub = new EnumMap<>(Hub.class);

    public HubRegistry(List<? extends HubAdapter> adapters) {
        Map<Hub, Long> counts = adapters.stream().collect(Collectors.groupingBy(HubAdapter::hub, Collectors.counting()));
        Set<Hub> duplicates = counts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("Two adapters for the same hub: " + duplicates);
        }
        adapters.forEach(a -> byHub.put(a.hub(), a));
    }

    public Set<Hub> hubs() {
        return Set.copyOf(byHub.keySet());
    }

    public HubAdapter adapter(Hub hub) {
        HubAdapter adapter = byHub.get(hub);
        if (adapter == null) {
            throw new HubException(hub, FailureClass.CONFIGURATION, null, "no adapter registered");
        }
        return adapter;
    }

    public <T> T require(Hub hub, Class<T> type) {
        HubAdapter adapter = adapter(hub);
        if (!type.isInstance(adapter)) {
            throw new HubException(hub, FailureClass.CONFIGURATION, null,
                    "adapter does not implement " + type.getSimpleName());
        }
        return type.cast(adapter);
    }

    public boolean supports(Hub hub, HubCapability capability) {
        HubAdapter adapter = byHub.get(hub);
        return adapter != null && adapter.capabilities().contains(capability);
    }

    /** Capability matrix for docs and health endpoints. */
    public Map<Hub, Set<HubCapability>> matrix() {
        Map<Hub, Set<HubCapability>> out = new LinkedHashMap<>();
        byHub.forEach((hub, adapter) -> out.put(hub, adapter.capabilities()));
        return out;
    }
}
