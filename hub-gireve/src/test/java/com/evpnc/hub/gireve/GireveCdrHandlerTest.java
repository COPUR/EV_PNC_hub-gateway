package com.evpnc.hub.gireve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.HubCdr;
import com.evpnc.hub.core.MutableClock;
import com.evpnc.hub.core.PricedBy;
import com.evpnc.hub.core.RecordingSink;
import com.evpnc.hub.core.TokenType;
import com.evpnc.hub.core.ocpi221.Ocpi221Compat;
import com.evpnc.hub.core.ocpi221.Ocpi221Config;
import com.evpnc.hub.core.resilience.CdrIntake;
import com.evpnc.hub.core.resilience.InMemoryIdempotencyStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GireveCdrHandlerTest {
    private final RecordingSink sink = new RecordingSink();
    private final GireveCdrHandler handler = new GireveCdrHandler(
            new Ocpi221Compat(Hub.GIREVE, new Ocpi221Config("FR", "107")),
            new CdrIntake(sink, new InMemoryIdempotencyStore()),
            new MutableClock());

    private static String cdr(String remark, String session) {
        return """
                {"country_code":"FR","party_id":"ABC","id":"CDR-1","session_id":"%s",
                 "start_date_time":"2026-11-02T09:00:00Z","end_date_time":"2026-11-02T09:30:00Z",
                 "cdr_token":{"country_code":"TR","party_id":"ZZZ","uid":"TRZZZC00000001X","type":"OTHER"},
                 "cdr_location":{"id":"LOC1","evse_uid":"E1","evse_id":"FR*ABC*E1"},
                 "currency":"EUR","total_energy":31.2,
                 "total_cost":{"excl_vat":312.00,"incl_vat":374.40},"remark":"%s"}"""
                .formatted(session, remark);
    }

    private static String cdr() {
        return cdr("[*] priced by hub", "GIR-SES-1");
    }

    @Test
    void aCdrIsConvertedToCanonical230AndHandedOnKeyedByTheHubSessionId() {
        OcpiResponse response = handler.handle(cdr());
        assertEquals(1000, response.statusCode());
        assertEquals(1, sink.received.size());
        HubCdr stored = sink.received.get(0);
        assertEquals(Hub.GIREVE, stored.hub());
        assertEquals("GIR-SES-1", stored.hubSessionId());
        assertEquals("FR*ABC*E1", stored.evseId());
        assertEquals(31.2, stored.energyKwh());
        assertEquals(Instant.parse("2026-11-02T09:00:00Z"), stored.sessionStart());
        assertEquals(TokenType.EMAID, stored.token().type());
        assertEquals(PricedBy.HUB, stored.pricedBy());
        JsonNode total = stored.ocpi().get("total_cost");
        assertEquals("312.0", total.get("before_taxes").asText());
        assertFalse(total.has("excl_vat"));
    }

    @Test
    void aStoreAndForwardReplayIsAcknowledgedAndKeptOnce() {
        handler.handle(cdr());
        assertEquals(1000, handler.handle(cdr()).statusCode());
        assertEquals(1, sink.received.size());
    }

    @Test
    void aPriceNotSignificantRemarkMarksTheCdrAsNeedingOurOwnPricing() {
        handler.handle(cdr("[?] n/a", "GIR-SES-1"));
        assertEquals(PricedBy.NOBODY, sink.received.get(0).pricedBy());
    }

    @Test
    void aFailingDownstreamIsStillASuccessForTheIopTheCdrIsParked() {
        sink.failing = true;
        assertEquals(1000, handler.handle(cdr()).statusCode());
        assertEquals(0, sink.received.size());
    }

    @Test
    void garbageAndACdrWithoutAnIdAre2001() {
        assertEquals(2001, handler.handle("<xml/>").statusCode());
        assertEquals(2001, handler.handle("{\"session_id\":\"x\"}").statusCode());
    }

    @Test
    void withoutASessionIdTheKeyFallsBackToTheCposCdrIdentity() {
        handler.handle("{\"country_code\":\"FR\",\"party_id\":\"ABC\",\"id\":\"CDR-9\"}");
        assertEquals("cdr:FR-ABC-CDR-9", sink.received.get(0).hubSessionId());
    }
}
