package com.evpnc.hub.hubject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.evpnc.hub.core.profile.Confidence;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HubjectProfileTest {
    private final HubjectProfile profile = HubjectProfile.load();

    @Test
    void authorizeWindowIs10s() {
        assertEquals(Duration.ofSeconds(10), profile.authorizeResponseTimeout());
    }

    @Test
    void ourRemoteClientTimeoutIsTheLargerCpoWindowPlus5sSo65sToday() {
        assertEquals(Duration.ofSeconds(65), profile.remoteClientTimeout());
    }

    @Test
    void rateLimitsComeFromTheAcceptableUsePolicyTable() {
        assertEquals(100, profile.rateLimitsPerMinute().get("AuthorizeRemoteStart"));
        assertEquals(200, profile.rateLimitsPerMinute().get("AuthorizeRemoteStop"));
        assertEquals(100, profile.rateLimitsPerMinute().get("PullEVSEData"));
    }

    @Test
    void thePullEvseDataLimitConflictIsRecordedInTheProfileAndNotResolvedInCode() {
        // 100 (AUP) against 25 (support page). The profile says: throttle to 25 until Hubject confirms. No code reads it yet.
        assertEquals(25, profile.document().integer("rate_limits_per_minute.conflict.PullEVSEData"));
        assertEquals(Confidence.FACT, profile.document().confidence("rate_limits_per_minute"));
    }

    @Test
    void entriesWeMustNotPretendToKnowAreListed() {
        assertFalse(profile.document().unknownPaths().isEmpty());
    }
}
