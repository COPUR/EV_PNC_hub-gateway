package com.evpnc.hub.core.ocpi221;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.FailureClass;
import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.HubException;
import com.evpnc.hub.core.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests. Each pins what the layer does with a GIREVE-shaped 2.2.1 payload today; an expectation that
 * looks wrong is recorded as a question in docs/refactoring-report.md, not "fixed" here.
 */
class Ocpi221CompatTest {
    private final Ocpi221Compat compat = new Ocpi221Compat(Hub.GIREVE, new Ocpi221Config("FR", "107"));

    private static ObjectNode json(String text) {
        return Json.parseObject(text);
    }

    @Test
    void cdrPricesBecomeBeforeTaxesPlusAVatTaxAmountAndTheEmaidTokenGetsItsType() {
        Converted result = compat.inbound(OcpiObject.CDR, json("""
                {"id":"CDR-1","session_id":"GIR-1","currency":"EUR",
                 "cdr_token":{"country_code":"TR","party_id":"ZZZ","uid":"TRZZZC00000001X","type":"OTHER"},
                 "total_cost":{"excl_vat":312.00,"incl_vat":374.40},
                 "total_energy_cost":{"excl_vat":300.0},
                 "remark":"[*] priced by hub"}"""));
        assertEquals(json("""
                {"id":"CDR-1","session_id":"GIR-1","currency":"EUR",
                 "cdr_token":{"country_code":"TR","party_id":"ZZZ","uid":"TRZZZC00000001X","type":"EMAID","contract_id":"TRZZZC00000001X"},
                 "total_cost":{"before_taxes":312.0,"taxes":[{"name":"VAT","amount":62.4}]},
                 "total_energy_cost":{"before_taxes":300.0,"taxes":[]},
                 "remark":"[*] priced by hub"}"""), result.json());
        assertTrue(result.sideData().isEmpty());
    }

    @Test
    void anOtherTokenThatIsNotAnEmaidStaysOther() {
        ObjectNode token = json("{\"uid\":\"04A2B3C4D5E6F7\",\"type\":\"OTHER\"}");
        assertEquals(token, compat.inbound(OcpiObject.TOKEN, token).json());
    }

    @Test
    void canonicalPricesAreNotConvertedTwice() {
        ObjectNode cdr = json("{\"total_cost\":{\"before_taxes\":10.0,\"taxes\":[]}}");
        assertEquals(cdr, compat.inbound(OcpiObject.CDR, cdr).json());
    }

    @Test
    void tariffLimitsBecomePriceLimitsASilentTaxIncludedGetsTheFallbackAndIsFlagged() {
        Converted result = compat.inbound(OcpiObject.TARIFF, json(
                "{\"id\":\"T1\",\"gireve_id\":\"G-T1\",\"min_price\":{\"excl_vat\":1.0,\"incl_vat\":1.2},\"max_price\":{\"excl_vat\":9.0}}"));
        assertEquals(json(
                "{\"id\":\"T1\",\"min_price\":{\"before_taxes\":1.0,\"after_taxes\":1.2},\"max_price\":{\"before_taxes\":9.0},\"tax_included\":\"N/A\"}"),
                result.json());
        assertEquals(Map.of("tax_included", "defaulted", "gireve_id:", "G-T1"), result.sideData());
    }

    @Test
    void aTariffThatStatesTaxIncludedKeepsIt() {
        Converted result = compat.inbound(OcpiObject.TARIFF, json("{\"id\":\"T2\",\"tax_included\":\"YES\"}"));
        assertEquals("YES", result.json().get("tax_included").asText());
        assertFalse(result.sideData().containsKey("tax_included"));
    }

    @Test
    void locationsGetThe230DefaultsAndTheGireveIdsMoveToSideData() {
        Converted result = compat.inbound(OcpiObject.LOCATION, json("""
                {"id":"LOC1","gireve_id":"G-L","evses":[{"uid":"E1","gireve_id":"G-E",
                 "connectors":[{"id":"1","gireve_id":"G-C","capabilities":["ISO_15118_2_PLUG_AND_CHARGE"]}]}]}"""));
        assertEquals(json("""
                {"id":"LOC1","evses":[{"uid":"E1","parking":[],"accepted_service_providers":[],
                 "connectors":[{"id":"1","capabilities":["ISO_15118_2_PLUG_AND_CHARGE"]}]}]}"""), result.json());
        assertEquals(Map.of("gireve_id:", "G-L", "gireve_id:/evses/0", "G-E", "gireve_id:/evses/0/connectors/0", "G-C"),
                result.sideData());
    }

    @Test
    void plugAndChargeCapabilityIsFoundOnTheEvseOrOnTheConnector() {
        assertTrue(compat.plugAndChargeCapable(json("{\"capabilities\":[\"ISO_15118_20_PLUG_AND_CHARGE\"],\"connectors\":[]}")));
        assertTrue(compat.plugAndChargeCapable(json("{\"connectors\":[{\"capabilities\":[\"ISO_15118_2_PLUG_AND_CHARGE\"]}]}")));
        assertFalse(compat.plugAndChargeCapable(json("{\"capabilities\":[\"RFID_READER\"],\"connectors\":[{\"id\":\"1\"}]}")));
    }

    @Test
    void credentialsFromTheHubGetHubPartyIdFromConfigTheHubRoleGoesToSideData() {
        Converted result = compat.inbound(OcpiObject.CREDENTIALS, json(
                "{\"token\":\"t\",\"roles\":[{\"role\":\"HUB\",\"country_code\":\"FR\",\"party_id\":\"007\"},{\"role\":\"CPO\",\"country_code\":\"FR\",\"party_id\":\"ABC\"}]}"));
        assertEquals(json(
                "{\"token\":\"t\",\"roles\":[{\"role\":\"CPO\",\"country_code\":\"FR\",\"party_id\":\"ABC\"}],\"hub_party_id\":{\"country_code\":\"FR\",\"party_id\":\"107\"}}"),
                result.json());
        assertEquals(Map.of("hub_role", "FR/007"), result.sideData());
    }

    @Test
    void credentialsWeSendLoseHubPartyId() {
        ObjectNode sent = compat.outbound(OcpiObject.CREDENTIALS,
                json("{\"token\":\"t\",\"hub_party_id\":{\"country_code\":\"FR\",\"party_id\":\"107\"}}"));
        assertEquals(json("{\"token\":\"t\"}"), sent);
    }

    @Test
    void anEmaidTokenGoesOutAsOtherWithUidAndContractIdBothSetToTheEmaid() {
        ObjectNode sent = compat.outbound(OcpiObject.TOKEN,
                json("{\"uid\":\"x\",\"type\":\"EMAID\",\"contract_id\":\"TRZZZC00000001X\"}"));
        assertEquals(json("{\"uid\":\"TRZZZC00000001X\",\"type\":\"OTHER\",\"contract_id\":\"TRZZZC00000001X\"}"), sent);
    }

    @Test
    void startSessionNeedsAnEvseUidAndConvertsItsToken() {
        HubException e = assertThrows(HubException.class,
                () -> compat.outbound(OcpiObject.START_SESSION, json("{\"location_id\":\"LOC1\"}")));
        assertEquals(FailureClass.NON_RETRYABLE, e.failureClass());
        assertEquals("EVSE_UID_REQUIRED", e.code());
        ObjectNode sent = compat.outbound(OcpiObject.START_SESSION, json(
                "{\"location_id\":\"LOC1\",\"evse_uid\":\"E1\",\"token\":{\"uid\":\"u\",\"type\":\"EMAID\",\"contract_id\":\"TRZZZC00000001X\"}}"));
        assertEquals("OTHER", sent.get("token").get("type").asText());
    }

    @Test
    void modulesEndpointsAndCommandsTheHubLacksAreRefusedBeforeAnyCall() {
        for (String module : new String[] {"payments", "chargingprofiles", "hubclientinfo"}) {
            assertEquals("MODULE_NOT_IMPLEMENTED", assertThrows(HubException.class, () -> compat.checkModule(module)).code());
        }
        assertEquals("ENDPOINT_NOT_IMPLEMENTED",
                assertThrows(HubException.class, () -> compat.checkEndpoint("sessions", "charging_preferences")).code());
        for (String command : new String[] {"RESERVE_NOW", "CANCEL_RESERVATION", "UNLOCK_CONNECTOR"}) {
            assertEquals("COMMAND_NOT_SUPPORTED", assertThrows(HubException.class, () -> compat.checkCommand(command)).code());
        }
        compat.checkCommand("START_SESSION");
        compat.checkCommand("STOP_SESSION");
        compat.checkModule("cdrs");
    }

    @Test
    void version230IsSpokenToTheHubAs221() {
        assertEquals("2.2.1", compat.wireVersion("2.3.0"));
        assertEquals("2.1.1", compat.wireVersion("2.1.1"));
    }
}
