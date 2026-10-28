package com.evpnc.hub.hubject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.FakeTransport;
import com.evpnc.hub.core.HubAdapter;
import com.evpnc.hub.core.HubAdapterContract;
import com.evpnc.hub.core.HubCapability;
import com.evpnc.hub.core.RecordingSink;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HubjectAdapterTest extends HubAdapterContract {
    private final RecordingSink sink = new RecordingSink();
    private final FakeTransport transport = new FakeTransport();
    private final HubjectAdapter adapter = new HubjectAdapter(
            "TRXYZ", TestSupport.ALLOW, sink, transport, TestSupport.newClock(),
            new com.evpnc.hub.core.resilience.InMemoryIdempotencyStore(), HubjectProfile.load(), AuthorizeStatusMapping.load());

    @Override
    protected HubAdapter adapter() {
        return adapter;
    }

    @Test
    void itClaimsOnlyWhatItHasBuilt() {
        assertEquals(
                Set.of(HubCapability.ONLINE_AUTHORIZE, HubCapability.REMOTE_START, HubCapability.REMOTE_STOP, HubCapability.CDR_RECEIVE),
                adapter.capabilities());
        assertFalse(adapter.capabilities().contains(HubCapability.PLUG_AND_CHARGE_CONTRACTS));
    }

    @Test
    void authorizeStartWorksEndToEndOnJsonText() {
        String body = """
                {"OperatorID":"TR*ZZZ","SessionID":"b2688855-7f00-0002-6d8e-48d883f6abb6",
                 "Identification":{"RFIDIdentification":{"UID":"04A2B3C4D5E6F7","RFID":"mifareFamily"}}}""";
        String answer = adapter.authorizeStart(body);
        assertTrue(answer.contains("\"AuthorizationStatus\":\"Authorized\""), answer);
        assertTrue(answer.contains("\"ProviderID\":\"TRXYZ\""), answer);
    }

    @Test
    void unparseableJsonIsAnsweredNotAuthorized022NeverAnException() {
        String answer = adapter.authorizeStart("{not json");
        assertTrue(answer.contains("\"NotAuthorized\"") && answer.contains("\"022\""), answer);
        String cdr = adapter.chargeDetailRecord("[]");
        assertTrue(cdr.contains("\"Result\":false") && cdr.contains("\"022\""), cdr);
    }

    @Test
    void unknownFieldsFromANewerOicpMinorVersionAreIgnored() {
        String body = """
                {"OperatorID":"TR*ZZZ","NewField":42,
                 "Identification":{"RFIDIdentification":{"UID":"04A2B3C4D5E6F7","RFID":"mifareFamily","Surprise":true}}}""";
        assertTrue(adapter.authorizeStart(body).contains("\"Authorized\""));
    }

    @Test
    void aCdrPostedAsJsonTextIsStoredOnceEvenIfPostedTwice() {
        String body = """
                {"SessionID":"b2688855-7f00-0002-6d8e-48d883f6abb6","EvseID":"TR*ZZZ*E0001*1",
                 "Identification":{"RemoteIdentification":{"EvcoID":"TRXYZC00000001A"}},
                 "ChargingStart":"2026-11-02T12:05:00+03:00","ChargingEnd":"2026-11-02T12:35:00+03:00",
                 "SessionStart":"2026-11-02T12:00:00+03:00","SessionEnd":"2026-11-02T12:36:00+03:00","ConsumedEnergy":31.2}""";
        for (int i = 0; i < 2; i++) {
            assertTrue(adapter.chargeDetailRecord(body).contains("\"Result\":true"));
        }
        assertEquals(1, sink.received.size());
    }
}
