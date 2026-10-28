package com.evpnc.hub.gireve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.evpnc.hub.core.AuthorizeDecision;
import com.evpnc.hub.core.FakeTransport;
import com.evpnc.hub.core.HubAdapter;
import com.evpnc.hub.core.HubAdapterContract;
import com.evpnc.hub.core.HubCapability;
import com.evpnc.hub.core.MutableClock;
import com.evpnc.hub.core.RecordingSink;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GireveAdapterTest extends HubAdapterContract {
    private final GireveAdapter adapter = new GireveAdapter(
            TestSupport.CONFIG, request -> new AuthorizeDecision.Allowed(), new RecordingSink(), new FakeTransport(), new MutableClock());

    @Override
    protected HubAdapter adapter() {
        return adapter;
    }

    @Test
    void itClaimsOnlyWhatItHasBuilt() {
        assertEquals(
                Set.of(HubCapability.ONLINE_AUTHORIZE, HubCapability.REMOTE_START, HubCapability.REMOTE_STOP,
                        HubCapability.CDR_RECEIVE, HubCapability.TOKEN_PUSH),
                adapter.capabilities());
        assertFalse(adapter.capabilities().contains(HubCapability.PLUG_AND_CHARGE_CONTRACTS));
        assertFalse(adapter.capabilities().contains(HubCapability.LOCATION_PULL));
    }

    @Test
    void authorizeWorksEndToEndThroughTheAdapter() {
        OcpiResponse response = adapter.authorize("04A2B3C4D5E6F7", "RFID", "{\"location_id\":\"LOC1\"}");
        assertEquals(1000, response.statusCode());
    }
}
