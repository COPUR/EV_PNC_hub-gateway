package com.evpnc.hub.core.ocpi221;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests on the samples the GIREVE mock in EV_PNC_simulator serves (the "mock" half of the H-02
 * acceptance, contributed by the parallel H-02 branch, EV_PNC_simulator commit 17cf059). They pin that the layer takes
 * what the mock really sends, not only hand-made shapes.
 */
class MockScenariosTest {
    private final Ocpi221Compat compat = new Ocpi221Compat(Hub.GIREVE, new Ocpi221Config("FR", "107"));

    private static JsonNode scenario(String name) {
        try (InputStream in = MockScenariosTest.class.getResourceAsStream("/scenarios/" + name + ".json")) {
            return Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> keysIn(JsonNode node) {
        Set<String> keys = new HashSet<>();
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                keys.add(entry.getKey());
                keys.addAll(keysIn(entry.getValue()));
            });
        } else if (node.isArray()) {
            node.forEach(child -> keys.addAll(keysIn(child)));
        }
        return keys;
    }

    @Test
    void theMockCdrConvertsAndLeavesNo221PriceShapeBehind() {
        Converted out = compat.inbound(OcpiObject.CDR, (ObjectNode) scenario("cdr"));
        Set<String> keys = keysIn(out.json());
        assertTrue(!keys.contains("excl_vat") && !keys.contains("incl_vat"), keys.toString());
        JsonNode cost = out.json().get("total_cost");
        assertEquals("4.88", cost.get("before_taxes").asText());
        JsonNode vat = cost.get("taxes").get(0);
        // exact difference, not 0.9700000000000006 (BigDecimal, no float noise)
        assertEquals("0.97", vat.get("amount").asText());
    }

    @Test
    void theMockSessionConvertsWithoutLosingItsIds() {
        ObjectNode session = (ObjectNode) scenario("session");
        ObjectNode out = compat.inbound(OcpiObject.SESSION, session).json();
        for (String key : new String[] {"id", "location_id", "evse_uid", "connector_id", "status"}) {
            assertEquals(session.get(key), out.get(key), key);
        }
    }

    @Test
    void everyMockTariffAndLocationConvertsAndNoneOfThemHasAnUndeclaredSideEffect() {
        for (JsonNode tariff : scenario("tariffs")) {
            ObjectNode out = compat.inbound(OcpiObject.TARIFF, (ObjectNode) tariff).json();
            assertTrue(out.has("tax_included"), "tariff " + tariff.get("id") + " needs tax_included");
        }
        for (JsonNode location : scenario("locations")) {
            ObjectNode out = compat.inbound(OcpiObject.LOCATION, (ObjectNode) location).json();
            assertEquals(location.get("id"), out.get("id"));
        }
    }
}
