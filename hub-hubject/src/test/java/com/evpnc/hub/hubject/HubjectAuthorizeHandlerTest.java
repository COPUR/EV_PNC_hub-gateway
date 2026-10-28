package com.evpnc.hub.hubject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.AuthChannel;
import com.evpnc.hub.core.AuthorizeDecision;
import com.evpnc.hub.core.AuthorizeRequest;
import com.evpnc.hub.core.DecisionOutcome;
import com.evpnc.hub.core.DecisionReason;
import com.evpnc.hub.core.TokenRef;
import com.evpnc.hub.core.TokenType;
import com.evpnc.hub.core.profile.ProfileDocument;
import com.evpnc.hub.core.resilience.GuardedPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class HubjectAuthorizeHandlerTest {
    private static final String SESSION = "b2688855-7f00-0002-6d8e-48d883f6abb6";
    private final List<AuthorizeRequest> seen = new ArrayList<>();
    private Supplier<AuthorizeDecision> decision = AuthorizeDecision.Allowed::new;

    private HubjectAuthorizeHandler handler() {
        return handler(HubjectProfile.load(), Duration.ofSeconds(2));
    }

    private HubjectAuthorizeHandler handler(HubjectProfile profile, Duration margin) {
        return new HubjectAuthorizeHandler(
                "TRXYZ",
                new GuardedPolicy(request -> {
                    seen.add(request);
                    return decision.get();
                }),
                profile,
                AuthorizeStatusMapping.load(),
                Clock.systemUTC(),
                margin);
    }

    private static OicpWire.AuthorizeStartRequest request(OicpWire.Identification identification, String sessionId) {
        return new OicpWire.AuthorizeStartRequest("TR*ZZZ", identification, sessionId, "cpo-1", null, "TR*ZZZ*E0001*1", null);
    }

    private static OicpWire.Identification rfidIdentification(String uid) {
        return new OicpWire.Identification(null, new OicpWire.Rfid(uid, "mifareFamily", null, null, null), null, null, null);
    }

    private static OicpWire.AuthorizeStartRequest rfid() {
        return request(rfidIdentification("04A2B3C4D5E6F7"), SESSION);
    }

    @Test
    void anAllowedRfidStartIsAuthorizedWithSuccessOurProviderIdAndTheSessionIdsEchoed() {
        OicpWire.AuthorizeStartResponse response = handler().handle(rfid());
        assertEquals("Authorized", response.authorizationStatus());
        assertEquals("000", response.statusCode().code());
        assertEquals("Success", response.statusCode().description());
        assertEquals("TRXYZ", response.providerId());
        assertEquals(SESSION, response.sessionId());
        assertEquals("cpo-1", response.cpoPartnerSessionId());
        assertNull(response.stopIdentifications());
    }

    @Test
    void thePolicySeesACanonicalRequestNotOicp() {
        handler().handle(rfid());
        AuthorizeRequest request = seen.get(0);
        assertEquals(AuthChannel.RFID, request.channel());
        assertEquals(new TokenRef("04A2B3C4D5E6F7", TokenType.RFID), request.token());
        assertEquals("TR*ZZZ*E0001*1", request.evseId());
        assertEquals("TR*ZZZ", request.operatorId());
        assertEquals(SESSION, request.hubSessionId());
    }

    @Test
    void aRefusalNamesTheReasonsInAdditionalInfoAndUsesTheMappedCode() {
        decision = () -> new AuthorizeDecision.Denied(DecisionOutcome.BLOCKED,
                List.of(DecisionReason.OPERATOR_BLOCKED, DecisionReason.PRICE_OVER_THRESHOLD));
        OicpWire.AuthorizeStartResponse response = handler().handle(rfid());
        assertEquals("NotAuthorized", response.authorizationStatus());
        assertEquals("210", response.statusCode().code());
        assertEquals("OPERATOR_BLOCKED, PRICE_OVER_THRESHOLD", response.statusCode().additionalInfo());
    }

    @Test
    void anUnknownCardIs102AnUnknownQrCode101AnUnknownPlugAndChargeContract105() {
        decision = () -> new AuthorizeDecision.Denied(DecisionOutcome.NOT_ALLOWED, List.of(DecisionReason.TOKEN_UNKNOWN));
        HubjectAuthorizeHandler h = handler();
        assertEquals("102", h.handle(rfid()).statusCode().code());
        OicpWire.Identification qr = new OicpWire.Identification(null, null, new OicpWire.QrCode("DE*8EO*Aet5e4*3", "1234", null), null, null);
        assertEquals("101", h.handle(request(qr, SESSION)).statusCode().code());
        OicpWire.Identification plc = OicpWire.Identification.ofPlugAndCharge("DE8EOCAET5E4AB3");
        assertEquals("105", h.handle(request(plc, SESSION)).statusCode().code());
    }

    @Test
    void aQrCodePinReachesThePolicyAsASecretAndNeverAppearsInTheAnswer() {
        OicpWire.Identification qr = new OicpWire.Identification(null, null, new OicpWire.QrCode("DE*8EO*Aet5e4*3", "98765", null), null, null);
        OicpWire.AuthorizeStartResponse response = handler().handle(request(qr, SESSION));
        AuthorizeRequest request = seen.get(0);
        assertEquals(AuthChannel.QR_CODE, request.channel());
        assertEquals("98765", request.pin().reveal());
        assertFalse(request.toString().contains("98765"));
        assertFalse(OicpJson.write(response).contains("98765"));
    }

    @Test
    void aPlugAndChargeIdentificationBecomesAnEmaidToken() {
        handler().handle(request(OicpWire.Identification.ofPlugAndCharge("DE8EOCAET5E4AB3"), SESSION));
        assertEquals(new TokenRef("DE8EOCAET5E4AB3", TokenType.EMAID, "DE8EOCAET5E4AB3"), seen.get(0).token());
        assertEquals(AuthChannel.PLUG_AND_CHARGE, seen.get(0).channel());
    }

    @Test
    void stopIdentificationsFromTheDecisionGoBackToHubject() {
        decision = () -> new AuthorizeDecision.Allowed(List.of(new TokenRef("04A2B3C4D5E6F7", TokenType.RFID)));
        OicpWire.AuthorizeStartResponse response = handler().handle(rfid());
        assertEquals("04A2B3C4D5E6F7", response.stopIdentifications().get(0).rfidMifareFamily().uid());
        assertEquals(1, response.stopIdentifications().size());
    }

    @Test
    void aUidThatBreaksTheContractPatternIs102AndThePolicyIsNotAsked() {
        OicpWire.AuthorizeStartResponse response = handler().handle(request(rfidIdentification("not-hex"), SESSION));
        assertEquals("NotAuthorized", response.authorizationStatus());
        assertEquals("102", response.statusCode().code());
        assertTrue(seen.isEmpty());
    }

    @Test
    void zeroOrTwoIdentificationVariantsAreADataError022() {
        OicpWire.Identification none = new OicpWire.Identification(null, null, null, null, null);
        OicpWire.Identification two = new OicpWire.Identification(
                null, new OicpWire.Rfid("04A2B3C4D5E6F7", "nfc", null, null, null), null, null, new OicpWire.Evco("DE8EOCAET5E4AB3"));
        for (OicpWire.Identification identification : List.of(none, two)) {
            OicpWire.AuthorizeStartResponse response = handler().handle(request(identification, SESSION));
            assertEquals("022", response.statusCode().code());
            assertEquals("NotAuthorized", response.authorizationStatus());
        }
        assertTrue(seen.isEmpty());
    }

    @Test
    void aSessionIdThatIsNotAGuidIs022() {
        assertEquals("022", handler().handle(request(rfidIdentification("04A2B3C4D5E6F7"), "x")).statusCode().code());
    }

    @Test
    void aFailingPolicyIsNotAuthorizedWith021NotAnError() {
        decision = () -> {
            throw new IllegalStateException("policy engine down");
        };
        OicpWire.AuthorizeStartResponse response = handler().handle(rfid());
        assertEquals("NotAuthorized", response.authorizationStatus());
        assertEquals("021", response.statusCode().code());
        String info = response.statusCode().additionalInfo() == null ? "" : response.statusCode().additionalInfo();
        assertFalse(info.contains("down"), "internal detail must not leak to the hub");
    }

    @Test
    void aPolicyThatMissesTheBudgetGets021InsideTheHubsWindow() throws IOException {
        String yaml;
        try (InputStream in = HubjectProfile.class.getClassLoader().getResourceAsStream("profiles/" + HubjectProfile.FILE)) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("authorize_start_response_s: {value: 10", "authorize_start_response_s: {value: 1");
        }
        HubjectProfile shortWindow = new HubjectProfile(ProfileDocument.parse("short", yaml));
        CountDownLatch never = new CountDownLatch(1);
        decision = () -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AuthorizeDecision.Allowed();
        };
        long started = System.nanoTime();
        OicpWire.AuthorizeStartResponse response = handler(shortWindow, Duration.ofMillis(800)).handle(rfid());
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        never.countDown();
        assertEquals("021", response.statusCode().code());
        assertEquals("NotAuthorized", response.authorizationStatus());
        assertTrue(tookMs < 1_000, "answered after " + tookMs + " ms; the hub's window is 1000 ms");
    }

    @Test
    void additionalInfoIsCutToTheContractBound() {
        OicpWire.StatusCode uncut = new OicpWire.StatusCode("021", "System error", "x".repeat(5000));
        assertEquals(5000, uncut.additionalInfo().length());
        assertEquals(1000, HubjectAuthorizeHandler.statusCode(OicpStatus.SYSTEM_ERROR, "x".repeat(5000)).additionalInfo().length());
    }

    @Test
    void theSafetyMarginMustBeShorterThanTheHubsWindow() {
        assertThrows(IllegalArgumentException.class, () -> handler(HubjectProfile.load(), Duration.ofSeconds(10)));
    }
}
