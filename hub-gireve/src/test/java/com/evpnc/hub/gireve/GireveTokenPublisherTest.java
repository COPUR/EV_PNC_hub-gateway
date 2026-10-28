package com.evpnc.hub.gireve;

import static com.evpnc.hub.gireve.TestSupport.CONFIG;
import static com.evpnc.hub.gireve.TestSupport.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.HttpRequestSpec;
import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.ocpi221.Ocpi221Compat;
import com.evpnc.hub.core.ocpi221.Ocpi221Config;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class GireveTokenPublisherTest {
    private final GireveTokenPublisher publisher = new GireveTokenPublisher(
            CONFIG, GireveProfile.load(), new Ocpi221Compat(Hub.GIREVE, new Ocpi221Config("FR", "107")));

    private ObjectNode token(String type, String uid) {
        return json("""
                {"country_code":"TR","party_id":"ZZZ","uid":"%s","type":"%s","contract_id":"C1","issuer":"EV_PNC",
                 "valid":true,"whitelist":"NEVER","last_updated":"2026-11-02T09:00:00Z"}"""
                .formatted(uid, type));
    }

    @Test
    void anRfidTokenIsPushedWithPutTheUidNormalisedToOurOwnPartyPath() {
        HttpRequestSpec request = publisher.putRequest(token("RFID", "04a2b3c4d5e6f7"), "r1", "c1");
        assertEquals("PUT", request.method());
        assertEquals("/ocpi/2.2.1/tokens/TR/ZZZ/04A2B3C4D5E6F7", request.path());
        assertTrue(request.body().contains("\"uid\":\"04A2B3C4D5E6F7\""));
        assertEquals("107", request.headers().get("OCPI-to-party-id"));
    }

    @Test
    void plugAndChargeTokensAreNeverPushedTheyAreAuthorisedInRealTime() {
        assertNull(publisher.putRequest(token("EMAID", "TRZZZC00000001X"), "r1", "c1"));
    }

    @Test
    void anRfidTokenWithAMalformedUidIsNotPushed() {
        assertNull(publisher.putRequest(token("RFID", "zz"), "r1", "c1"));
    }

    @Test
    void appTokensKeepTheirUid() {
        HttpRequestSpec request = publisher.putRequest(token("APP_USER", "app-user-1"), "r1", "c1");
        assertEquals("/ocpi/2.2.1/tokens/TR/ZZZ/app-user-1", request.path());
    }
}
