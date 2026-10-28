package com.evpnc.hub.core.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.HubCdr;
import com.evpnc.hub.core.TestData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CdrIntakeTest {
    private final List<HubCdr> delivered = new ArrayList<>();
    private boolean failing = false;
    private final CdrIntake intake = new CdrIntake(cdr -> {
        if (failing) {
            throw new IllegalStateException("payments down");
        }
        delivered.add(cdr);
    }, new InMemoryIdempotencyStore());

    @Test
    void firstDeliveryIsStoredAResendIsADuplicate() {
        assertEquals(IntakeResult.STORED, intake.receive(TestData.hubCdr()));
        assertEquals(IntakeResult.DUPLICATE, intake.receive(TestData.hubCdr()));
        assertEquals(1, delivered.size());
    }

    @Test
    void theSameSessionIdOnTwoHubsIsTwoRecords() {
        assertEquals(IntakeResult.STORED, intake.receive(TestData.hubCdr(Hub.HUBJECT, "S1")));
        assertEquals(IntakeResult.STORED, intake.receive(TestData.hubCdr(Hub.GIREVE, "S1")));
    }

    @Test
    void aFailingSinkParksTheRecordReplayDeliversItOnceTheSinkIsBack() {
        failing = true;
        assertEquals(IntakeResult.PARKED, intake.receive(TestData.hubCdr(Hub.HUBJECT, "S2")));
        assertEquals(1, intake.parkedCount());
        assertEquals(0, intake.replay(), "still failing");
        assertEquals(1, intake.parkedCount());
        failing = false;
        assertEquals(1, intake.replay());
        assertEquals(0, intake.parkedCount());
        assertEquals(List.of("S2"), delivered.stream().map(HubCdr::hubSessionId).toList());
    }

    @Test
    void aParkedRecordThatIsResentByTheHubIsNotStoredTwice() {
        failing = true;
        intake.receive(TestData.hubCdr(Hub.HUBJECT, "S3"));
        failing = false;
        assertEquals(IntakeResult.DUPLICATE, intake.receive(TestData.hubCdr(Hub.HUBJECT, "S3")));
        intake.replay();
        assertEquals(1, delivered.size());
    }
}
