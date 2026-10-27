package com.evpnc.hub.gireve

import com.evpnc.hub.core.ocpi221.OcpiParty
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GireveProfileTest {
    private val profile = GireveProfile.load()

    @Test
    fun `the hub is FR 107 in pre-production and FR 007 in production`() {
        assertEquals(OcpiParty("FR", "107"), profile.hubParty(GireveEnvironment.PRE_PRODUCTION))
        assertEquals(OcpiParty("FR", "007"), profile.hubParty(GireveEnvironment.PRODUCTION))
    }

    @Test
    fun `rules the adapter relies on`() {
        assertEquals(listOf(4, 7), profile.uidByteLengths)
        assertTrue(profile.locationReferenceRequired)
        assertEquals(setOf("RESERVE_NOW", "CANCEL_RESERVATION", "UNLOCK_CONNECTOR"), profile.unsupportedCommands)
        assertEquals(Duration.ofSeconds(120), profile.meterUpdateMinInterval)
        assertEquals(setOf(425, 429), profile.waitStatuses)
        assertTrue("chargingprofiles" !in profile.supportedModules)
    }

    @Test
    fun `store and forward retries after 5, 10 and 20 minutes and then gives up`() {
        assertEquals(Duration.ofMinutes(5), profile.retrySchedule.delayAfter(1))
        assertEquals(Duration.ofMinutes(20), profile.retrySchedule.delayAfter(3))
        assertNull(profile.retrySchedule.delayAfter(4))
    }

    @Test
    fun `open questions stay visible in the profile`() {
        // The direction of FromIOP/ToIOP and the hubclientinfo contradiction are ambiguities, not facts.
        assertTrue(profile.document.has("registration.ambiguity"))
        assertTrue(profile.document.has("modules.contradictory"))
    }
}
