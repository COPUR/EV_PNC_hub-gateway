package com.evpnc.hub.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What every hub adapter must satisfy, whatever the hub. Subclass it in the adapter module and provide the adapter; a
 * new hub (e-clearing.net in phase 5) joins the framework by passing this class first.
 */
public abstract class HubAdapterContract {
    protected abstract HubAdapter adapter();

    /** Capabilities that must be backed by a port the adapter implements. */
    private static final Map<HubCapability, Class<?>> PORT_BACKED_CAPABILITIES =
            Map.of(HubCapability.REMOTE_START, RemoteSessionPort.class, HubCapability.REMOTE_STOP, RemoteSessionPort.class);

    @Test
    void aCapabilityThatNeedsAPortIsBackedByThatPort() {
        HubAdapter adapter = adapter();
        PORT_BACKED_CAPABILITIES.forEach((capability, port) -> {
            if (adapter.capabilities().contains(capability)) {
                assertTrue(port.isInstance(adapter),
                        adapter.hub() + " claims " + capability + " but does not implement " + port.getSimpleName());
            }
        });
    }

    @Test
    void theAdapterCanBeRegisteredAndFoundByItsHub() {
        HubAdapter adapter = adapter();
        HubRegistry registry = new HubRegistry(java.util.List.of(adapter));
        assertEquals(adapter, registry.adapter(adapter.hub()));
        assertEquals(adapter.capabilities(), registry.matrix().get(adapter.hub()));
    }

    @Test
    void theAdapterDeclaresAtLeastOneCapabilityAndLoadsItsProfile() {
        HubAdapter adapter = adapter();
        assertFalse(adapter.capabilities().isEmpty(), "no capability declared");
        assertFalse(adapter.profile().name().isBlank(), "profile has no name");
    }
}
