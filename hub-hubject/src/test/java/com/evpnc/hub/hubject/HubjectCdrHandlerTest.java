package com.evpnc.hub.hubject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.HubCdr;
import com.evpnc.hub.core.PricedBy;
import com.evpnc.hub.core.RecordingSink;
import com.evpnc.hub.core.resilience.CdrIntake;
import com.evpnc.hub.core.resilience.InMemoryIdempotencyStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HubjectCdrHandlerTest {
    private static final String SESSION = "b2688855-7f00-0002-6d8e-48d883f6abb6";
    private final RecordingSink sink = new RecordingSink();
    private final HubjectCdrHandler handler = new HubjectCdrHandler(new CdrIntake(sink, new InMemoryIdempotencyStore()));

    private static OicpWire.ChargeDetailRecord cdr(String sessionId, String sessionStart,
            List<OicpWire.SignedMeteringValue> signed, OicpWire.CalibrationLawVerificationInfo calibration) {
        return new OicpWire.ChargeDetailRecord(
                sessionId,
                "TR*ZZZ*E0001*1",
                new OicpWire.Identification(null, new OicpWire.Rfid("04A2B3C4D5E6F7", "mifareFamily", null, null, null), null, null, null),
                "2026-11-02T12:05:00+03:00",
                "2026-11-02T12:35:00+03:00",
                sessionStart,
                "2026-11-02T12:36:00+03:00",
                31.2,
                null, null, null,
                100.0, 131.2,
                signed, calibration,
                "DE*HUB", null);
    }

    private static OicpWire.ChargeDetailRecord cdr() {
        return cdr(SESSION, "2026-11-02T12:00:00+03:00", null, null);
    }

    @Test
    void aCdrIsStoredWithPlus0300TimestampsAsInstantsAndAcknowledgedWithTheSessionId() {
        OicpWire.Acknowledgement ack = handler.handle(cdr());
        assertTrue(ack.result());
        assertEquals("000", ack.statusCode().code());
        assertEquals(SESSION, ack.sessionId());
        HubCdr stored = sink.received.get(0);
        assertEquals(Hub.HUBJECT, stored.hub());
        assertEquals(Instant.parse("2026-11-02T09:00:00Z"), stored.sessionStart());
        assertEquals(Instant.parse("2026-11-02T09:36:00Z"), stored.sessionEnd());
        assertEquals(31.2, stored.energyKwh());
        assertEquals(PricedBy.NOBODY, stored.pricedBy(), "OICP CDRs carry no price; payments prices them");
    }

    @Test
    void aResendIsAcknowledgedPositivelyAndStoredOnce() {
        handler.handle(cdr());
        OicpWire.Acknowledgement ack = handler.handle(cdr());
        assertTrue(ack.result());
        assertEquals(1, sink.received.size());
        assertEquals("duplicate, already received", ack.statusCode().additionalInfo());
    }

    @Test
    void aFailingDownstreamStillGetsAPositiveAnswerBecauseHubjectNeverResends() {
        sink.failing = true;
        OicpWire.Acknowledgement ack = handler.handle(cdr("c2688855-7f00-0002-6d8e-48d883f6abb6", "2026-11-02T12:00:00+03:00", null, null));
        assertTrue(ack.result());
        assertEquals("accepted, processing deferred", ack.statusCode().additionalInfo());
    }

    @Test
    void aCdrWithoutASessionIdIsTheOnlyThingRefused() {
        OicpWire.Acknowledgement ack = handler.handle(cdr(" ", "2026-11-02T12:00:00+03:00", null, null));
        assertFalse(ack.result());
        assertEquals("022", ack.statusCode().code());
    }

    @Test
    void unreadableTimestampsAreKeptAsTextUnderExtensionsNotLost() {
        handler.handle(cdr(SESSION, "yesterday-ish", null, null));
        HubCdr stored = sink.received.get(0);
        assertNull(stored.sessionStart());
        String oicp = stored.extensions().get("oicp").toString();
        assertTrue(oicp.contains("yesterday-ish"), oicp);
    }

    @Test
    void signedMeteringAndTheCalibrationInfoTravelInTheOicpExtension() {
        handler.handle(cdr(SESSION, "2026-11-02T12:00:00+03:00",
                List.of(new OicpWire.SignedMeteringValue("QUJD", "Start"), new OicpWire.SignedMeteringValue("REVG", "End")),
                new OicpWire.CalibrationLawVerificationInfo("CAL-1", null, null, null, null)));
        String oicp = sink.received.get(0).extensions().get("oicp").toString();
        assertTrue(oicp.contains("QUJD") && oicp.contains("REVG") && oicp.contains("CAL-1"), oicp);
        assertNotNull(sink.received.get(0).token());
    }
}
