package com.evpnc.hub.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.profile.ProfileDocument;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HubRegistryTest {
    private static class Fake implements HubAdapter {
        private final Hub hub;
        private final Set<HubCapability> capabilities;
        private final ProfileDocument profile = new ProfileDocument("fake", Map.of());

        Fake(Hub hub, Set<HubCapability> capabilities) {
            this.hub = hub;
            this.capabilities = capabilities;
        }

        @Override
        public Hub hub() {
            return hub;
        }

        @Override
        public Set<HubCapability> capabilities() {
            return capabilities;
        }

        @Override
        public ProfileDocument profile() {
            return profile;
        }
    }

    private static final class Remote extends Fake implements RemoteSessionPort {
        Remote(Hub hub) {
            super(hub, Set.of(HubCapability.REMOTE_START));
        }

        @Override
        public RemoteResult start(RemoteStart command) {
            return new RemoteResult.Accepted(null);
        }

        @Override
        public RemoteResult stop(RemoteStop command) {
            return new RemoteResult.Accepted(null);
        }
    }

    @Test
    void capabilityMatrixShowsWhatEachHubImplements() {
        HubRegistry registry = new HubRegistry(List.of(new Remote(Hub.HUBJECT), new Fake(Hub.GIREVE, Set.of(HubCapability.CDR_RECEIVE))));
        assertTrue(registry.supports(Hub.HUBJECT, HubCapability.REMOTE_START));
        assertFalse(registry.supports(Hub.GIREVE, HubCapability.REMOTE_START));
        assertEquals(Set.of(Hub.HUBJECT, Hub.GIREVE), registry.matrix().keySet());
    }

    @Test
    void anAdapterIsFoundByThePortItImplements() {
        HubRegistry registry = new HubRegistry(List.of(new Remote(Hub.HUBJECT), new Fake(Hub.GIREVE, Set.of())));
        registry.require(Hub.HUBJECT, RemoteSessionPort.class);
        HubException e = assertThrows(HubException.class, () -> registry.require(Hub.GIREVE, RemoteSessionPort.class));
        assertEquals(FailureClass.CONFIGURATION, e.failureClass());
    }

    @Test
    void aHubWithoutAnAdapterAndADuplicateAdapterAreConfigurationErrors() {
        assertThrows(HubException.class, () -> new HubRegistry(List.of()).adapter(Hub.E_CLEARING));
        assertThrows(IllegalArgumentException.class,
                () -> new HubRegistry(List.of(new Fake(Hub.GIREVE, Set.of()), new Fake(Hub.GIREVE, Set.of()))));
    }
}
