package com.evpnc.hub.gireve;

import static com.evpnc.hub.gireve.TestSupport.CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.AuthorizeDecision;
import com.evpnc.hub.core.AuthorizeRequest;
import com.evpnc.hub.core.DecisionOutcome;
import com.evpnc.hub.core.DecisionReason;
import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.MutableClock;
import com.evpnc.hub.core.TokenRef;
import com.evpnc.hub.core.TokenType;
import com.evpnc.hub.core.ocpi221.Ocpi221Compat;
import com.evpnc.hub.core.ocpi221.Ocpi221Config;
import com.evpnc.hub.core.resilience.GuardedPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class GireveAuthorizeHandlerTest {
    private final List<AuthorizeRequest> seen = new ArrayList<>();
    private Supplier<AuthorizeDecision> decision =
            () -> new AuthorizeDecision.Allowed(List.of(), "AUTH-1");
    private final String locations = "{\"location_id\":\"LOC1\",\"evse_uids\":[\"E1\"]}";

    private GireveAuthorizeHandler handler(Duration budget) {
        return new GireveAuthorizeHandler(
                CONFIG.withAuthorizeBudget(budget),
                GireveProfile.load(),
                new Ocpi221Compat(Hub.GIREVE, new Ocpi221Config("FR", "107")),
                new GuardedPolicy(request -> {
                    seen.add(request);
                    return decision.get();
                }),
                new MutableClock());
    }

    private GireveAuthorizeHandler handler() {
        return handler(Duration.ofSeconds(2));
    }

    private static JsonNode data(OcpiResponse response) {
        return response.data();
    }

    @Test
    void anAllowedTokenGetsAuthorizationInfoWithTheTokenTheLocationAndOurReference() {
        OcpiResponse response = handler().handle("04A2B3C4D5E6F7", "RFID", locations);
        assertEquals(1000, response.statusCode());
        JsonNode info = data(response);
        assertEquals("ALLOWED", info.get("allowed").asText());
        assertEquals("04A2B3C4D5E6F7", info.get("token").get("uid").asText());
        assertEquals("TR", info.get("token").get("country_code").asText());
        assertEquals("LOC1", info.get("location").get("location_id").asText());
        assertEquals("AUTH-1", info.get("authorization_reference").asText());
    }

    @Test
    void thePolicySeesACanonicalTokenAuthorizeRequest() {
        handler().handle("04a2b3c4d5e6f7", "RFID", locations);
        assertEquals(1, seen.size());
        AuthorizeRequest request = seen.get(0);
        assertEquals(Hub.GIREVE, request.hub());
        assertEquals(new TokenRef("04A2B3C4D5E6F7", TokenType.RFID), request.token());
        assertEquals("E1", request.evseId());
        assertEquals("LOC1", request.operatorId());
    }

    @Test
    void aRequestWithoutLocationReferencesIs2002AndThePolicyIsNotAsked() {
        for (String body : Arrays.asList(null, "", "{}", "{\"evse_uids\":[\"E1\"]}")) {
            assertEquals(2002, handler().handle("04A2B3C4D5E6F7", "RFID", body).statusCode(), "body: " + body);
        }
        assertTrue(seen.isEmpty());
    }

    @Test
    void anUnknownTokenIs2004WithNoData() {
        decision = () -> new AuthorizeDecision.Denied(DecisionOutcome.NOT_ALLOWED, List.of(DecisionReason.TOKEN_UNKNOWN));
        OcpiResponse response = handler().handle("04A2B3C4D5E6F7", "RFID", locations);
        assertEquals(2004, response.statusCode());
        assertNull(response.data());
    }

    @Test
    void aMalformedRfidUidIsUnknownWithoutAskingThePolicy() {
        assertEquals(2004, handler().handle("not-hex", "RFID", locations).statusCode());
        assertTrue(seen.isEmpty());
    }

    @Test
    void aKnownButRefusedTokenIsAuthorizationInfoWithTheRefusalAndNoLocation() {
        Map<DecisionOutcome, String> expectations = new LinkedHashMap<>();
        expectations.put(DecisionOutcome.BLOCKED, "BLOCKED");
        expectations.put(DecisionOutcome.NO_CREDIT, "NO_CREDIT");
        expectations.put(DecisionOutcome.NOT_ALLOWED, "NOT_ALLOWED");
        expectations.forEach((outcome, expected) -> {
            decision = () -> new AuthorizeDecision.Denied(outcome, List.of(DecisionReason.OPERATOR_BLOCKED));
            OcpiResponse response = handler().handle("04A2B3C4D5E6F7", "RFID", locations);
            assertEquals(1000, response.statusCode());
            assertEquals(expected, data(response).get("allowed").asText());
            assertNull(data(response).get("location"));
        });
    }

    @Test
    void aPlugAndChargeTokenArrivesAsOtherWithAnEmaidAndReachesThePolicyAsEmaid() {
        handler().handle("TRZZZC00000001X", "OTHER", locations);
        assertEquals(new TokenRef("TRZZZC00000001X", TokenType.EMAID, "TRZZZC00000001X"), seen.get(0).token());
    }

    @Test
    void anEmaidTokenGoesBackToTheIopAsOtherWithUidAndContractIdEqualToTheEmaid() {
        OcpiResponse response = handler().handle("TRZZZC00000001X", "OTHER", locations);
        JsonNode token = data(response).get("token");
        assertEquals("OTHER", token.get("type").asText());
        assertEquals("TRZZZC00000001X", token.get("uid").asText());
        assertEquals("TRZZZC00000001X", token.get("contract_id").asText());
    }

    @Test
    void aPolicyThatMissesTheBudgetIsAServerErrorNotAGuess() {
        CountDownLatch never = new CountDownLatch(1);
        decision = () -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AuthorizeDecision.Allowed();
        };
        OcpiResponse response = handler(Duration.ofMillis(100)).handle("04A2B3C4D5E6F7", "RFID", locations);
        never.countDown();
        assertEquals(3000, response.statusCode());
        assertNull(response.data());
    }

    @Test
    void theAnswerEnvelopeSerialisesWithStatusCodeAndTimestamp() {
        String text = handler().handle("04A2B3C4D5E6F7", "RFID", locations).toJson();
        assertTrue(text.contains("\"status_code\":1000") && text.contains("\"timestamp\""), text);
        assertNotNull(OcpiResponse.parse(text));
    }
}
