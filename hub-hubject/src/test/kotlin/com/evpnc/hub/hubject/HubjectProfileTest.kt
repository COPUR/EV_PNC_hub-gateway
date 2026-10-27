package com.evpnc.hub.hubject

import com.evpnc.hub.core.profile.Confidence
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HubjectProfileTest {
    private val profile = HubjectProfile.load()

    @Test
    fun `authorize window is 10 s`() = assertEquals(Duration.ofSeconds(10), profile.authorizeResponseTimeout)

    @Test
    fun `our remote client timeout is the larger CPO window plus 5 s, so 65 s today`() {
        assertEquals(Duration.ofSeconds(65), profile.remoteClientTimeout)
    }

    @Test
    fun `rate limits come from the Acceptable Use Policy table`() {
        assertEquals(100, profile.rateLimitsPerMinute.getValue("AuthorizeRemoteStart"))
        assertEquals(200, profile.rateLimitsPerMinute.getValue("AuthorizeRemoteStop"))
        assertEquals(100, profile.rateLimitsPerMinute.getValue("PullEVSEData"))
    }

    @Test
    fun `the PullEVSEData limit conflict is recorded in the profile and not resolved in code`() {
        // 100 (AUP) against 25 (support page). The profile says: throttle to 25 until Hubject confirms. No code reads it yet.
        assertEquals(25, profile.document.int("rate_limits_per_minute.conflict.PullEVSEData"))
        assertEquals(Confidence.FACT, profile.document.confidence("rate_limits_per_minute"))
    }

    @Test
    fun `entries we must not pretend to know are listed`() {
        assertTrue(profile.document.unknownPaths().isNotEmpty())
    }
}
