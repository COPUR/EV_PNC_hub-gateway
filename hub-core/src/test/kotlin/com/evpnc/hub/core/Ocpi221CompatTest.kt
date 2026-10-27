package com.evpnc.hub.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ocpi221CompatTest {
    private val profile = HubProfile.fromContracts("gireve-ocpi-2.2.1.profile.yaml")
    private val compat = Ocpi221Compat(profile)

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun scenario(name: String): JsonElement =
        Json.parseToJsonElement(javaClass.getResource("/scenarios/$name.json")!!.readText())

    private fun JsonObject.text(vararg path: String): String? =
        path.fold(this as JsonElement?) { cur, key -> (cur as? JsonObject)?.get(key) }?.jsonPrimitive?.content

    private fun leaks(e: JsonElement, keys: Set<String>): List<String> = when (e) {
        is JsonObject -> e.flatMap { (k, v) -> (if (k in keys) listOf(k) else emptyList()) + leaks(v, keys) }
        is JsonArray -> e.flatMap { leaks(it, keys) }
        else -> emptyList()
    }

    // ---- OUT ------------------------------------------------------------------------------------------------------

    @Test
    fun `version 2_3_0 is spoken as 2_2_1`() {
        assertEquals("2.2.1", compat.versionToWire("2.3.0"))
    }

    @Test
    fun `modules the hub does not implement are blocked, including those added in 2_3_0`() {
        for (m in listOf("chargingprofiles", "hubclientinfo", "payments")) assertFailsWith<Blocked> { compat.requireModule(m) }
        compat.requireModule("locations")
        assertFailsWith<Blocked> { compat.requireEndpoint("sessions", "charging_preferences") }
    }

    @Test
    fun `only the commands in the profile may be sent`() {
        compat.requireCommand("START_SESSION")
        for (c in listOf("RESERVE_NOW", "CANCEL_RESERVATION", "UNLOCK_CONNECTOR")) assertFailsWith<Blocked> { compat.requireCommand(c) }
    }

    @Test
    fun `hub_party_id is dropped from outgoing credentials`() {
        val out = compat.outbound(ObjectType.CREDENTIALS, obj("""{"token":"t","url":"u","roles":[],"hub_party_id":"FR107"}"""))
        assertFalse("hub_party_id" in out.json)
        assertEquals(listOf("credentials.hub_party_id:drop"), out.applied)
    }

    @Test
    fun `an EMAID token goes out as OTHER with uid and contract_id both the eMAID`() {
        val token = obj("""{"country_code":"TR","party_id":"TST","uid":"TR-TST-C00000001-1","type":"EMAID","contract_id":"TR-TST-C00000001-1"}""")
        val out = compat.outbound(ObjectType.TOKEN, token)
        assertEquals("OTHER", out.json.text("type"))
        assertEquals("TR-TST-C00000001-1", out.json.text("uid"))
        assertEquals(listOf("token.type:EMAID->OTHER"), out.applied)
        val rfid = obj("""{"uid":"04A1B2C3","type":"RFID","contract_id":"TR-TST-C00000001-1"}""")
        assertEquals(rfid, compat.outbound(ObjectType.TOKEN, rfid).json)
    }

    @Test
    fun `an EMAID token whose uid and contract_id disagree is refused rather than guessed`() {
        val token = obj("""{"uid":"TR-TST-C00000002-2","type":"EMAID","contract_id":"TR-TST-C00000001-1"}""")
        assertTrue(assertFailsWith<ConversionError> { compat.outbound(ObjectType.TOKEN, token) }.message!!.contains("eMAID"))
    }

    @Test
    fun `START_SESSION needs an evse_uid for this hub`() {
        val ok = obj("""{"response_url":"https://x","location_id":"L","evse_uid":"E","token":{}}""")
        assertEquals(ok, compat.outbound(ObjectType.START_SESSION, ok).json)
        assertFailsWith<ConversionError> { compat.outbound(ObjectType.START_SESSION, obj("""{"response_url":"https://x","location_id":"L","token":{}}""")) }
        assertFailsWith<ConversionError> { compat.outbound(ObjectType.START_SESSION, obj("""{"location_id":"L","evse_uid":""}""")) }
    }

    // ---- IN -------------------------------------------------------------------------------------------------------

    @Test
    fun `credentials from a 2_2_1 hub get the hub party id of the environment`() {
        val wire = obj("""{"token":"t","url":"u","roles":[{"role":"HUB","party_id":"107","country_code":"FR"}]}""")
        assertEquals("FR107", compat.inbound(ObjectType.CREDENTIALS, wire).json.text("hub_party_id"))
        assertEquals("FR007", Ocpi221Compat(profile, Environment.PRODUCTION).inbound(ObjectType.CREDENTIALS, wire).json.text("hub_party_id"))
        // the HUB role is accepted as it is, and an id the hub did send is not overwritten
        val sent = obj("""{"token":"t","url":"u","roles":[{"role":"HUB"}],"hub_party_id":"FR999"}""")
        val out = compat.inbound(ObjectType.CREDENTIALS, sent)
        assertEquals("FR999", out.json.text("hub_party_id"))
        assertEquals(emptyList(), out.applied)
    }

    @Test
    fun `an OTHER token with an eMAID uid becomes EMAID, any other OTHER stays`() {
        val emaid = obj("""{"uid":"FR*ABC*C12345678*7","type":"OTHER"}""")
        assertEquals("EMAID", compat.inbound(ObjectType.TOKEN, emaid).json.text("type"))
        val other = obj("""{"uid":"BADGE-42","type":"OTHER"}""")
        assertEquals(other, compat.inbound(ObjectType.TOKEN, other).json)
        val rfid = obj("""{"uid":"04A1B2C3","type":"RFID"}""")
        assertEquals(rfid, compat.inbound(ObjectType.TOKEN, rfid).json)
    }

    @Test
    fun `a CDR price becomes before_taxes plus a VAT line computed exactly`() {
        val cdr = scenario("cdr").jsonObject
        val out = compat.inbound(ObjectType.CDR, cdr)
        assertEquals("4.88", out.json.text("total_cost", "before_taxes"))
        val tax = out.json["total_cost"]!!.jsonObject["taxes"]!!.jsonArray.single().jsonObject
        assertEquals("VAT", tax.text("name"))
        assertEquals("0.97", tax.text("amount")) // 5.85 - 4.88, not 0.9700000000000006
        assertEquals(emptyList(), leaks(out.json, setOf("excl_vat", "incl_vat")))
        // fields the hub never sends stay absent
        for (f in listOf("credit", "credit_reference_id", "home_charging_compensation")) assertFalse(f in out.json)
        assertEquals("[*] priced by the hub", out.json.text("remark"))
    }

    @Test
    fun `a price without incl_vat has no tax lines and one without excl_vat is refused`() {
        val s = obj("""{"id":"S","total_cost":{"excl_vat":10}}""")
        val out = compat.inbound(ObjectType.SESSION, s).json
        assertEquals("10", out.text("total_cost", "before_taxes"))
        assertEquals(0, out["total_cost"]!!.jsonObject["taxes"]!!.jsonArray.size)
        assertFailsWith<ConversionError> { compat.inbound(ObjectType.SESSION, obj("""{"total_cost":{"incl_vat":10}}""")) }
    }

    @Test
    fun `converting a canonical price again changes nothing`() {
        val once = compat.inbound(ObjectType.CDR, scenario("cdr").jsonObject).json
        val twice = compat.inbound(ObjectType.CDR, once)
        assertEquals(once, twice.json)
        assertEquals(emptyList(), twice.applied)
    }

    @Test
    fun `every CDR cost field is converted and cdr_token is checked for eMAID`() {
        val cdr = obj(
            """{"total_cost":{"excl_vat":1,"incl_vat":1.2},"total_energy_cost":{"excl_vat":0.5},"total_time_cost":{"excl_vat":0.2},
               "total_fixed_cost":{"excl_vat":0.1},"total_parking_cost":{"excl_vat":0.1},"total_reservation_cost":{"excl_vat":0.1},
               "cdr_token":{"uid":"FR*ABC*C12345678*7","type":"OTHER"}}""",
        )
        val out = compat.inbound(ObjectType.CDR, cdr).json
        assertEquals(emptyList(), leaks(out, setOf("excl_vat", "incl_vat")))
        assertEquals("EMAID", out.text("cdr_token", "type"))
    }

    @Test
    fun `a tariff gets tax_included UNKNOWN, price limits and an external reference`() {
        val tariff = obj(
            """{"id":"T","type":"REGULAR","currency":"EUR","elements":[],"gireve_id":"gir-1",
               "min_price":{"excl_vat":1.5,"incl_vat":1.8},"max_price":{"excl_vat":20}}""",
        )
        val out = compat.inbound(ObjectType.TARIFF, tariff)
        assertEquals("UNKNOWN", out.json.text("tax_included"))
        assertEquals("1.5", out.json.text("min_price", "before_taxes"))
        assertEquals("1.8", out.json.text("min_price", "after_taxes"))
        assertEquals("20", out.json.text("max_price", "before_taxes"))
        assertFalse("after_taxes" in out.json["max_price"]!!.jsonObject)
        assertEquals("GIREVE", out.json.text("external_ref", "system"))
        assertEquals("gir-1", out.json.text("external_ref", "id"))
        assertFalse("gireve_id" in out.json)
    }

    @Test
    fun `a tariff that already says whether tax is included keeps its value`() {
        val out = compat.inbound(ObjectType.TARIFF, obj("""{"id":"T","tax_included":"YES"}"""))
        assertEquals("YES", out.json.text("tax_included"))
    }

    @Test
    fun `a location gets the 2_3_0 defaults, external references on every level and the PnC capability on its connectors`() {
        val loc = scenario("locations").jsonArray.first().jsonObject
        val out = compat.inbound(ObjectType.LOCATION, loc)
        val evse = out.json["evses"]!!.jsonArray.single().jsonObject
        assertEquals(JsonArray(emptyList()), evse["parking"])
        assertEquals(JsonArray(emptyList()), evse["accepted_service_providers"])
        assertEquals("GIREVE", out.json.text("external_ref", "system"))
        assertEquals(emptyList(), leaks(out.json, setOf("gireve_id")))
        val connector = evse["connectors"]!!.jsonArray.single().jsonObject
        val caps = connector["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("ISO_15118_2_PLUG_AND_CHARGE"), caps)
        assertTrue("connector.capabilities:from-evse" in out.applied)
    }

    @Test
    fun `a PnC capability the connector already has is not repeated and long addresses pass through`() {
        val address = "x".repeat(200)
        val loc = obj(
            """{"id":"L","address":"$address","evses":[{"uid":"E","capabilities":["ISO_15118_2_PLUG_AND_CHARGE","RFID_READER"],
               "connectors":[{"id":"1","capabilities":["ISO_15118_2_PLUG_AND_CHARGE"]}]}]}""",
        )
        val out = compat.inbound(ObjectType.LOCATION, loc)
        val conn = out.json["evses"]!!.jsonArray.single().jsonObject["connectors"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("ISO_15118_2_PLUG_AND_CHARGE"), conn["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(address, out.json.text("address"))
    }

    // ---- scenarios on what the mock serves --------------------------------------------------------------------------

    @Test
    fun `everything the GIREVE mock serves converts to the canonical shape`() {
        for (loc in scenario("locations").jsonArray) {
            val out = compat.inbound(ObjectType.LOCATION, loc.jsonObject).json
            assertEquals(emptyList(), leaks(out, setOf("gireve_id")))
            assertTrue(out["external_ref"] != null)
        }
        for (t in scenario("tariffs").jsonArray) {
            val out = compat.inbound(ObjectType.TARIFF, t.jsonObject).json
            assertEquals("UNKNOWN", out.text("tax_included"))
            assertEquals(emptyList(), leaks(out, setOf("gireve_id")))
        }
        val session = compat.inbound(ObjectType.SESSION, scenario("session").jsonObject).json
        assertEquals("RFID", session.text("cdr_token", "type"))
    }

    @Test
    fun `the canonical gaps are listed for the model`() {
        assertTrue(Ocpi221Compat.CANONICAL_NEEDS.any { it.where == "Tariff.tax_included" })
        assertEquals(JsonPrimitive("UNKNOWN").content, Ocpi221Compat.TAX_UNKNOWN)
    }
}
