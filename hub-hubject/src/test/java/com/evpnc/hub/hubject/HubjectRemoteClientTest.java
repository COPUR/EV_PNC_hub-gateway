package com.evpnc.hub.hubject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.FailureClass;
import com.evpnc.hub.core.FakeTransport;
import com.evpnc.hub.core.HttpRequestSpec;
import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.HubException;
import com.evpnc.hub.core.MutableClock;
import com.evpnc.hub.core.RemoteRejection;
import com.evpnc.hub.core.RemoteResult;
import com.evpnc.hub.core.RemoteStart;
import com.evpnc.hub.core.RemoteStop;
import com.evpnc.hub.core.TokenRef;
import com.evpnc.hub.core.TokenType;
import com.evpnc.hub.core.TransportTimeout;
import com.evpnc.hub.core.resilience.Throttler;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HubjectRemoteClientTest {
    private static final String SESSION = "b2688855-7f00-0002-6d8e-48d883f6abb6";
    private final FakeTransport transport = new FakeTransport();
    private final MutableClock clock = TestSupport.newClock();
    private final RemoteStart start = new RemoteStart(
            Hub.HUBJECT, "req-1", new TokenRef("TRXYZC00000001A", TokenType.APP_USER, "TRXYZC00000001A"), "TR*ZZZ*E0001*1");

    private HubjectRemoteClient client() {
        return client(Map.of());
    }

    private HubjectRemoteClient client(Map<String, Integer> limits) {
        return new HubjectRemoteClient("TRXYZ", transport, new Throttler(limits, clock));
    }

    private static String ack(String code, boolean result, String session) {
        return "{\"Result\":" + result + ",\"StatusCode\":{\"Code\":\"" + code + "\"}"
                + (session == null ? "" : ",\"SessionID\":\"" + session + "\"") + "}";
    }

    @Test
    void remoteStartPostsToOurProviderPathSendsNoSessionIdAndARemoteIdentification() {
        transport.answers(200, ack("000", true, SESSION));
        RemoteResult result = client().start(start);
        HttpRequestSpec request = transport.sent.get(0);
        assertEquals("POST", request.method());
        assertEquals("/charging/v21/providers/TRXYZ/authorize-remote/start", request.path());
        assertFalse(request.body().contains("\"SessionID\""), request.body());
        assertTrue(request.body().contains("\"RemoteIdentification\":{\"EvcoID\":\"TRXYZC00000001A\"}"), request.body());
        assertTrue(request.body().contains("\"EMPPartnerSessionID\":\"req-1\""));
        assertEquals(new RemoteResult.Accepted(SESSION), result);
    }

    @Test
    void remoteStopSendsTheHubSessionOurProviderAndTheEvse() {
        transport.answers(200, ack("000", true, null));
        client().stop(new RemoteStop(Hub.HUBJECT, SESSION, "TR*ZZZ*E0001*1"));
        assertEquals("/charging/v21/providers/TRXYZ/authorize-remote/stop", transport.sent.get(0).path());
        assertTrue(transport.sent.get(0).body().contains("\"SessionID\":\"" + SESSION + "\""));
    }

    @Test
    void failureCodesMapToDriverFacingReasons() {
        Map<String, RemoteRejection> expected = new LinkedHashMap<>();
        expected.put("210", RemoteRejection.NO_VALID_CONTRACT);
        expected.put("300", RemoteRejection.PARTNER_UNKNOWN);
        expected.put("310", RemoteRejection.PARTNER_UNREACHABLE);
        expected.put("501", RemoteRejection.EVSE_UNREACHABLE);
        expected.put("510", RemoteRejection.NO_VEHICLE_CONNECTED);
        expected.put("601", RemoteRejection.EVSE_RESERVED);
        expected.put("602", RemoteRejection.EVSE_IN_USE);
        expected.put("603", RemoteRejection.EVSE_UNKNOWN);
        expected.put("604", RemoteRejection.EVSE_NOT_ROAMABLE);
        expected.put("700", RemoteRejection.EVSE_OUT_OF_SERVICE);
        expected.put("021", RemoteRejection.OTHER);
        expected.forEach((code, reason) -> {
            transport.answers(200, ack(code, false, null));
            assertEquals(new RemoteResult.Rejected(code, reason), client().start(start), code);
        });
    }

    @Test
    void aCodeOutsideTheOfficialListIsAFailureNotACrash() {
        transport.answers(200, ack("777", false, null));
        assertEquals(new RemoteResult.Rejected("777", RemoteRejection.OTHER), client().start(start));
    }

    @Test
    void code017IsAConfigurationErrorThatMustNotBeRetried() {
        transport.answers(200, ack("017", false, null));
        HubException e = assertThrows(HubException.class, () -> client().start(start));
        assertEquals(FailureClass.CONFIGURATION, e.failureClass());
    }

    @Test
    void aTimeoutLeavesTheStateUnknownInsteadOfFailingTheStart() {
        transport.next = () -> {
            throw new TransportTimeout("65 s");
        };
        assertInstanceOf(RemoteResult.UnknownState.class, client().start(start));
    }

    @Test
    void code429And5xxAreRetryableOtherStatusesAreNotGarbageIsNotRetried() {
        transport.answers(429, "");
        assertEquals(FailureClass.RETRYABLE, assertThrows(HubException.class, () -> client().start(start)).failureClass());
        transport.answers(503, "");
        assertEquals(FailureClass.RETRYABLE, assertThrows(HubException.class, () -> client().start(start)).failureClass());
        transport.answers(400, "");
        assertEquals(FailureClass.NON_RETRYABLE, assertThrows(HubException.class, () -> client().start(start)).failureClass());
        transport.answers(200, "<html>");
        assertEquals(FailureClass.NON_RETRYABLE, assertThrows(HubException.class, () -> client().start(start)).failureClass());
    }

    @Test
    void callsOverTheAcceptableUsePolicyLimitAreHeldBackBeforeReachingTheHub() {
        transport.answers(200, ack("000", true, null));
        HubjectRemoteClient client = client(Map.of("AuthorizeRemoteStart", 2));
        client.start(start);
        client.start(start);
        HubException e = assertThrows(HubException.class, () -> client.start(start));
        assertEquals("THROTTLED", e.code());
        assertEquals(FailureClass.RETRYABLE, e.failureClass());
        assertEquals(2, transport.sent.size());
        clock.advance(Duration.ofSeconds(30));
        client.start(start);
        assertEquals(3, transport.sent.size());
    }
}
