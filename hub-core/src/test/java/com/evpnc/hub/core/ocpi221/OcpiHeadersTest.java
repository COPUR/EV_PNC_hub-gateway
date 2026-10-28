package com.evpnc.hub.core.ocpi221;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OcpiHeadersTest {
    private final OcpiParty us = new OcpiParty("TR", "ZZZ");
    private final OcpiParty hub = new OcpiParty("FR", "107");

    @Test
    void routingHeadersArePresentOnCdrsAndTokens() {
        Map<String, String> headers = OcpiHeaders.request("cdrs", us, hub, "secret", "r1", "c1");
        assertEquals("TR", headers.get("OCPI-from-country-code"));
        assertEquals("107", headers.get("OCPI-to-party-id"));
        assertEquals("r1", headers.get("X-Request-ID"));
        assertEquals("c1", headers.get("X-Correlation-ID"));
    }

    @Test
    void credentialsAndVersionsCarryNoRoutingHeadersButStillTheTracingIds() {
        for (String module : List.of("credentials", "versions")) {
            Map<String, String> headers = OcpiHeaders.request(module, us, hub, "secret", "r1", "c1");
            assertFalse(headers.keySet().stream().anyMatch(k -> k.startsWith("OCPI-")), module);
            assertTrue(headers.containsKey("X-Request-ID"));
        }
    }

    @Test
    void theTokenIsBase64Encoded() {
        String auth = OcpiHeaders.request("cdrs", us, hub, "secret", "r1", "c1").get("Authorization");
        assertEquals("Token " + Base64.getEncoder().encodeToString("secret".getBytes(StandardCharsets.UTF_8)), auth);
    }

    @Test
    void anAnswerMustEchoBothIds() {
        Map<String, String> sent = Map.of("X-Request-ID", "r1", "X-Correlation-ID", "c1");
        assertEquals(List.of(), OcpiHeaders.checkEcho(sent, Map.of("x-request-id", "r1", "x-correlation-id", "c1")));
        List<String> problems = OcpiHeaders.checkEcho(sent, Map.of("X-Request-ID", "other"));
        assertEquals(2, problems.size(), problems.toString());
    }
}
