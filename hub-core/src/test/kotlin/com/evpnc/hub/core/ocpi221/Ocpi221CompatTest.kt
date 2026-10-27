package com.evpnc.hub.core.ocpi221

import com.evpnc.hub.core.FailureClass
import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.HubException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Characterization tests. Each pins what the layer does with a GIREVE-shaped 2.2.1 payload today; an expectation that
 * looks wrong is recorded as a question in docs/refactoring-report.md, not "fixed" here.
 */
class Ocpi221CompatTest {
    private val compat = Ocpi221Compat(Hub.GIREVE, Ocpi221Config("FR", "107"))

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `CDR prices become before_taxes plus a VAT tax amount and the eMAID token gets its type`() {
        val result = compat.inbound(
            OcpiObject.CDR,
            json(
                """{"id":"CDR-1","session_id":"GIR-1","currency":"EUR",
                    "cdr_token":{"country_code":"TR","party_id":"ZZZ","uid":"TRZZZC00000001X","type":"OTHER"},
                    "total_cost":{"excl_vat":312.00,"incl_vat":374.40},
                    "total_energy_cost":{"excl_vat":300.0},
                    "remark":"[*] priced by hub"}""",
            ),
        )
        assertEquals(
            json(
                """{"id":"CDR-1","session_id":"GIR-1","currency":"EUR",
                    "cdr_token":{"country_code":"TR","party_id":"ZZZ","uid":"TRZZZC00000001X","type":"EMAID","contract_id":"TRZZZC00000001X"},
                    "total_cost":{"before_taxes":312.0,"taxes":[{"name":"VAT","amount":62.4}]},
                    "total_energy_cost":{"before_taxes":300.0,"taxes":[]},
                    "remark":"[*] priced by hub"}""",
            ),
            result.json,
        )
        assertTrue(result.sideData.isEmpty())
    }

    @Test
    fun `an OTHER token that is not an eMAID stays OTHER`() {
        val token = json("""{"uid":"04A2B3C4D5E6F7","type":"OTHER"}""")
        assertEquals(token, compat.inbound(OcpiObject.TOKEN, token).json)
    }

    @Test
    fun `canonical prices are not converted twice`() {
        val cdr = json("""{"total_cost":{"before_taxes":10.0,"taxes":[]}}""")
        assertEquals(cdr, compat.inbound(OcpiObject.CDR, cdr).json)
    }

    @Test
    fun `tariff limits become price limits, a silent tax_included gets the fallback and is flagged`() {
        val result = compat.inbound(
            OcpiObject.TARIFF,
            json("""{"id":"T1","gireve_id":"G-T1","min_price":{"excl_vat":1.0,"incl_vat":1.2},"max_price":{"excl_vat":9.0}}"""),
        )
        assertEquals(
            json("""{"id":"T1","min_price":{"before_taxes":1.0,"after_taxes":1.2},"max_price":{"before_taxes":9.0},"tax_included":"N/A"}"""),
            result.json,
        )
        assertEquals(mapOf("tax_included" to "defaulted", "gireve_id:" to "G-T1"), result.sideData)
    }

    @Test
    fun `a tariff that states tax_included keeps it`() {
        val result = compat.inbound(OcpiObject.TARIFF, json("""{"id":"T2","tax_included":"YES"}"""))
        assertEquals("YES", result.json["tax_included"]!!.toString().trim('"'))
        assertFalse("tax_included" in result.sideData)
    }

    @Test
    fun `locations get the 2_3_0 defaults and the GIREVE ids move to side data`() {
        val result = compat.inbound(
            OcpiObject.LOCATION,
            json(
                """{"id":"LOC1","gireve_id":"G-L","evses":[{"uid":"E1","gireve_id":"G-E",
                    "connectors":[{"id":"1","gireve_id":"G-C","capabilities":["ISO_15118_2_PLUG_AND_CHARGE"]}]}]}""",
            ),
        )
        assertEquals(
            json(
                """{"id":"LOC1","evses":[{"uid":"E1","parking":[],"accepted_service_providers":[],
                    "connectors":[{"id":"1","capabilities":["ISO_15118_2_PLUG_AND_CHARGE"]}]}]}""",
            ),
            result.json,
        )
        assertEquals(
            mapOf("gireve_id:" to "G-L", "gireve_id:/evses/0" to "G-E", "gireve_id:/evses/0/connectors/0" to "G-C"),
            result.sideData,
        )
    }

    @Test
    fun `Plug and Charge capability is found on the EVSE or on the connector`() {
        assertTrue(compat.plugAndChargeCapable(json("""{"capabilities":["ISO_15118_20_PLUG_AND_CHARGE"],"connectors":[]}""")))
        assertTrue(compat.plugAndChargeCapable(json("""{"connectors":[{"capabilities":["ISO_15118_2_PLUG_AND_CHARGE"]}]}""")))
        assertFalse(compat.plugAndChargeCapable(json("""{"capabilities":["RFID_READER"],"connectors":[{"id":"1"}]}""")))
    }

    @Test
    fun `credentials from the hub get hub_party_id from config, the HUB role goes to side data`() {
        val result = compat.inbound(
            OcpiObject.CREDENTIALS,
            json("""{"token":"t","roles":[{"role":"HUB","country_code":"FR","party_id":"007"},{"role":"CPO","country_code":"FR","party_id":"ABC"}]}"""),
        )
        assertEquals(
            json("""{"token":"t","roles":[{"role":"CPO","country_code":"FR","party_id":"ABC"}],"hub_party_id":{"country_code":"FR","party_id":"107"}}"""),
            result.json,
        )
        assertEquals(mapOf("hub_role" to "FR/007"), result.sideData)
    }

    @Test
    fun `credentials we send lose hub_party_id`() {
        val sent = compat.outbound(OcpiObject.CREDENTIALS, json("""{"token":"t","hub_party_id":{"country_code":"FR","party_id":"107"}}"""))
        assertEquals(json("""{"token":"t"}"""), sent)
    }

    @Test
    fun `an EMAID token goes out as OTHER with uid and contract_id both set to the eMAID`() {
        val sent = compat.outbound(OcpiObject.TOKEN, json("""{"uid":"x","type":"EMAID","contract_id":"TRZZZC00000001X"}"""))
        assertEquals(json("""{"uid":"TRZZZC00000001X","type":"OTHER","contract_id":"TRZZZC00000001X"}"""), sent)
    }

    @Test
    fun `START_SESSION needs an evse_uid and converts its token`() {
        val e = assertFailsWith<HubException> {
            compat.outbound(OcpiObject.START_SESSION, json("""{"location_id":"LOC1"}"""))
        }
        assertEquals(FailureClass.NON_RETRYABLE, e.failureClass)
        assertEquals("EVSE_UID_REQUIRED", e.code)
        val sent = compat.outbound(
            OcpiObject.START_SESSION,
            json("""{"location_id":"LOC1","evse_uid":"E1","token":{"uid":"u","type":"EMAID","contract_id":"TRZZZC00000001X"}}"""),
        )
        assertEquals("OTHER", (sent["token"] as JsonObject)["type"]!!.toString().trim('"'))
    }

    @Test
    fun `modules, endpoints and commands the hub lacks are refused before any call`() {
        listOf("payments", "chargingprofiles", "hubclientinfo").forEach { module ->
            assertEquals("MODULE_NOT_IMPLEMENTED", assertFailsWith<HubException> { compat.checkModule(module) }.code)
        }
        assertEquals("ENDPOINT_NOT_IMPLEMENTED", assertFailsWith<HubException> { compat.checkEndpoint("sessions", "charging_preferences") }.code)
        listOf("RESERVE_NOW", "CANCEL_RESERVATION", "UNLOCK_CONNECTOR").forEach { command ->
            assertEquals("COMMAND_NOT_SUPPORTED", assertFailsWith<HubException> { compat.checkCommand(command) }.code)
        }
        compat.checkCommand("START_SESSION")
        compat.checkCommand("STOP_SESSION")
        compat.checkModule("cdrs")
    }

    @Test
    fun `2_3_0 is spoken to the hub as 2_2_1`() {
        assertEquals("2.2.1", compat.wireVersion("2.3.0"))
        assertEquals("2.1.1", compat.wireVersion("2.1.1"))
    }
}
