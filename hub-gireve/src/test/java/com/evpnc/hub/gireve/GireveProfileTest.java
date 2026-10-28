package com.evpnc.hub.gireve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.ocpi221.OcpiParty;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GireveProfileTest {
    private final GireveProfile profile = GireveProfile.load();

    @Test
    void theHubIsFr107InPreProductionAndFr007InProduction() {
        assertEquals(new OcpiParty("FR", "107"), profile.hubParty(GireveEnvironment.PRE_PRODUCTION));
        assertEquals(new OcpiParty("FR", "007"), profile.hubParty(GireveEnvironment.PRODUCTION));
    }

    @Test
    void rulesTheAdapterReliesOn() {
        assertEquals(List.of(4, 7), profile.uidByteLengths());
        assertTrue(profile.locationReferenceRequired());
        assertEquals(Set.of("RESERVE_NOW", "CANCEL_RESERVATION", "UNLOCK_CONNECTOR"), profile.unsupportedCommands());
        assertEquals(Duration.ofSeconds(120), profile.meterUpdateMinInterval());
        assertEquals(Set.of(425, 429), profile.waitStatuses());
        assertFalse(profile.supportedModules().contains("chargingprofiles"));
    }

    @Test
    void storeAndForwardRetriesAfter5_10And20MinutesAndThenGivesUp() {
        assertEquals(Duration.ofMinutes(5), profile.retrySchedule().delayAfter(1));
        assertEquals(Duration.ofMinutes(20), profile.retrySchedule().delayAfter(3));
        assertNull(profile.retrySchedule().delayAfter(4));
    }

    @Test
    void openQuestionsStayVisibleInTheProfile() {
        // The direction of FromIOP/ToIOP and the hubclientinfo contradiction are ambiguities, not facts.
        assertTrue(profile.document().has("registration.ambiguity"));
        assertTrue(profile.document().has("modules.contradictory"));
    }
}
