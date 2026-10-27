package com.evpnc.hub.core.ocpi221

import com.evpnc.hub.core.Hub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Characterization tests on the samples the GIREVE mock in EV_PNC_simulator serves (the "mock" half of the H-02
 * acceptance, contributed by the parallel H-02 branch, EV_PNC_simulator commit 17cf059). They pin that the layer takes
 * what the mock really sends, not only hand-made shapes.
 */
class MockScenariosTest {
    private val compat = Ocpi221Compat(Hub.GIREVE, Ocpi221Config("FR", "107"))

    private fun scenario(name: String): JsonElement =
        Json.parseToJsonElement(javaClass.getResource("/scenarios/$name.json")!!.readText())

    private fun keysIn(e: JsonElement): Set<String> = when (e) {
        is JsonObject -> e.keys + e.values.flatMap { keysIn(it) }
        is JsonArray -> e.flatMap { keysIn(it) }.toSet()
        else -> emptySet()
    }

    private fun JsonObject.cost(field: String): JsonObject = getValue(field).jsonObject

    @Test
    fun `the mock CDR converts and leaves no 2_2_1 price shape behind`() {
        val out = compat.inbound(OcpiObject.CDR, scenario("cdr").jsonObject)
        assertTrue(keysIn(out.json).intersect(setOf("excl_vat", "incl_vat")).isEmpty())
        val cost = out.json.cost("total_cost")
        assertEquals("4.88", cost.getValue("before_taxes").jsonPrimitive.content)
        val vat = cost.getValue("taxes").jsonArray.single().jsonObject
        // exact difference, not 0.9700000000000006 (BigDecimal, no float noise)
        assertEquals("0.97", vat.getValue("amount").jsonPrimitive.content)
    }

    @Test
    fun `the mock session converts without losing its ids`() {
        val session = scenario("session").jsonObject
        val out = compat.inbound(OcpiObject.SESSION, session).json
        for (key in listOf("id", "location_id", "evse_uid", "connector_id", "status")) {
            assertEquals(session.getValue(key), out.getValue(key), key)
        }
    }

    @Test
    fun `every mock tariff and location converts and none of them has an undeclared side effect`() {
        for (t in scenario("tariffs").jsonArray) {
            val out = compat.inbound(OcpiObject.TARIFF, t.jsonObject)
            assertTrue(out.json.containsKey("tax_included"), "tariff ${t.jsonObject["id"]} needs tax_included")
        }
        for (l in scenario("locations").jsonArray) {
            val out = compat.inbound(OcpiObject.LOCATION, l.jsonObject)
            assertEquals(l.jsonObject.getValue("id"), out.json.getValue("id"))
        }
    }
}
