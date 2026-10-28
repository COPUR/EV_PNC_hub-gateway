package com.evpnc.hub.gireve;

import static com.evpnc.hub.gireve.TestSupport.CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.FailureClass;
import com.evpnc.hub.core.FakeTransport;
import com.evpnc.hub.core.HttpRequestSpec;
import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.HubException;
import com.evpnc.hub.core.Json;
import com.evpnc.hub.core.MutableClock;
import com.evpnc.hub.core.RemoteRejection;
import com.evpnc.hub.core.RemoteResult;
import com.evpnc.hub.core.RemoteStart;
import com.evpnc.hub.core.RemoteStop;
import com.evpnc.hub.core.TokenRef;
import com.evpnc.hub.core.TokenType;
import com.evpnc.hub.core.TransportTimeout;
import com.evpnc.hub.core.ocpi221.Ocpi221Compat;
import com.evpnc.hub.core.ocpi221.Ocpi221Config;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class GireveCommandClientTest {
    private final FakeTransport transport = new FakeTransport();
    private final GireveCommandClient client = new GireveCommandClient(
            CONFIG, GireveProfile.load(), new Ocpi221Compat(Hub.GIREVE, new Ocpi221Config("FR", "107")), transport, new MutableClock());
    private final RemoteStart start = new RemoteStart(
            Hub.GIREVE, "req-1", new TokenRef("TRZZZC00000001X", TokenType.EMAID, "TRZZZC00000001X"), "E1", "LOC1", null);

    private static String envelope(int code, String result) {
        String data = result == null ? "" : ",\"data\":{\"result\":\"" + result + "\",\"timeout\":30}";
        return "{\"status_code\":" + code + ",\"status_message\":\"m\"" + data + "}";
    }

    private static String envelope(int code) {
        return envelope(code, null);
    }

    private HubException startFails() {
        return assertThrows(HubException.class, () -> client.start(start));
    }

    private RemoteStart withoutLocation() {
        return new RemoteStart(start.hub(), start.requestId(), start.token(), start.evseId(), null, null);
    }

    @Test
    void startSessionGoesToTheCommandsEndpointWithRoutingHeadersEvseUidAndAnOtherTypedEmaidToken() {
        transport.answers(200, envelope(1000, "ACCEPTED"));
        RemoteResult result = client.start(start);
        assertEquals(1, transport.sent.size());
        HttpRequestSpec request = transport.sent.get(0);
        assertEquals("POST", request.method());
        assertEquals("/ocpi/2.2.1/commands/START_SESSION", request.path());
        assertEquals("FR", request.headers().get("OCPI-to-country-code"));
        assertEquals("107", request.headers().get("OCPI-to-party-id"));
        assertEquals("TR", request.headers().get("OCPI-from-country-code"));
        assertEquals("req-1", request.headers().get("X-Request-ID"));
        JsonNode body = Json.parse(request.body());
        assertEquals("E1", body.get("evse_uid").asText());
        assertEquals("LOC1", body.get("location_id").asText());
        assertEquals("OTHER", body.get("token").get("type").asText());
        assertEquals("https://emsp.example/ocpi/2.2.1/commands/START_SESSION/req-1", body.get("response_url").asText());
        assertEquals(new RemoteResult.Accepted(null), result);
    }

    @Test
    void startSessionWithoutALocationIdFailsBeforeAnyCall() {
        HubException e = assertThrows(HubException.class, () -> client.start(withoutLocation()));
        assertEquals("LOCATION_REQUIRED", e.code());
        assertTrue(transport.sent.isEmpty());
    }

    @Test
    void stopSessionSendsTheHubSessionId() {
        transport.answers(200, envelope(1000, "ACCEPTED"));
        client.stop(new RemoteStop(Hub.GIREVE, "GIR-SES-1", "E1"));
        assertEquals("/ocpi/2.2.1/commands/STOP_SESSION", transport.sent.get(0).path());
        assertTrue(transport.sent.get(0).body().contains("\"session_id\":\"GIR-SES-1\""));
    }

    @Test
    void commandResultsOtherThanAcceptedAreRejections() {
        for (String result : List.of("REJECTED", "NOT_SUPPORTED", "UNKNOWN_SESSION")) {
            transport.answers(200, envelope(1000, result));
            assertEquals(new RemoteResult.Rejected(result, RemoteRejection.OTHER), client.start(start));
        }
    }

    @Test
    void hubStatusCodes4001_4002And4003MapToUnknownReceiverUnknownStateAndNotConnected() {
        transport.answers(200, envelope(4001));
        assertEquals(RemoteRejection.PARTNER_UNKNOWN, ((RemoteResult.Rejected) client.start(start)).reason());
        transport.answers(200, envelope(4003));
        assertEquals(RemoteRejection.PARTNER_UNREACHABLE, ((RemoteResult.Rejected) client.start(start)).reason());
        transport.answers(200, envelope(4002));
        assertInstanceOf(RemoteResult.UnknownState.class, client.start(start));
    }

    @Test
    void aTransportTimeoutLeavesTheStateUnknown() {
        transport.next = () -> {
            throw new TransportTimeout("30 s");
        };
        assertInstanceOf(RemoteResult.UnknownState.class, client.start(start));
    }

    @Test
    void code425And429AreWaitSignals5xxAnd3xxxRetryableOtherClientErrorsAreNot() {
        for (int status : List.of(425, 429, 502)) {
            transport.answers(status, "");
            assertEquals(FailureClass.RETRYABLE, startFails().failureClass(), String.valueOf(status));
        }
        transport.answers(200, envelope(3000));
        assertEquals(FailureClass.RETRYABLE, startFails().failureClass());
        transport.answers(200, envelope(2001));
        assertEquals(FailureClass.NON_RETRYABLE, startFails().failureClass());
        transport.answers(404, "");
        assertEquals(FailureClass.NON_RETRYABLE, startFails().failureClass());
        transport.answers(200, "<html>");
        assertEquals(FailureClass.NON_RETRYABLE, startFails().failureClass());
    }

    @Test
    void theHubTokenIsBase64EncodedInAuthorizationAndNeverInTheBody() {
        transport.answers(200, envelope(1000, "ACCEPTED"));
        client.start(start);
        HttpRequestSpec request = transport.sent.get(0);
        assertTrue(request.headers().get("Authorization").startsWith("Token "));
        assertTrue(!request.body().contains("hub-secret"));
    }
}
