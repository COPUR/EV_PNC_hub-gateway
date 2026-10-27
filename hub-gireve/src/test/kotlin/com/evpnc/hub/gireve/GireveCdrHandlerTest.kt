package com.evpnc.hub.gireve

import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.MutableClock
import com.evpnc.hub.core.PricedBy
import com.evpnc.hub.core.RecordingSink
import com.evpnc.hub.core.TokenType
import com.evpnc.hub.core.ocpi221.Ocpi221Compat
import com.evpnc.hub.core.ocpi221.Ocpi221Config
import com.evpnc.hub.core.resilience.CdrIntake
import com.evpnc.hub.core.resilience.InMemoryIdempotencyStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GireveCdrHandlerTest {
    private val sink = RecordingSink()
    private val handler = GireveCdrHandler(
        Ocpi221Compat(Hub.GIREVE, Ocpi221Config("FR", "107")),
        CdrIntake(sink, InMemoryIdempotencyStore()),
        MutableClock(),
    )

    private fun cdr(remark: String = "[*] priced by hub", session: String = "GIR-SES-1") = """
        {"country_code":"FR","party_id":"ABC","id":"CDR-1","session_id":"$session",
         "start_date_time":"2026-11-02T09:00:00Z","end_date_time":"2026-11-02T09:30:00Z",
         "cdr_token":{"country_code":"TR","party_id":"ZZZ","uid":"TRZZZC00000001X","type":"OTHER"},
         "cdr_location":{"id":"LOC1","evse_uid":"E1","evse_id":"FR*ABC*E1"},
         "currency":"EUR","total_energy":31.2,
         "total_cost":{"excl_vat":312.00,"incl_vat":374.40},"remark":"$remark"}
    """.trimIndent()

    @Test
    fun `a CDR is converted to canonical 2_3_0 and handed on keyed by the hub session id`() {
        val response = handler.handle(cdr())
        assertEquals(1000, response.statusCode)
        val stored = sink.received.single()
        assertEquals(Hub.GIREVE, stored.hub)
        assertEquals("GIR-SES-1", stored.hubSessionId)
        assertEquals("FR*ABC*E1", stored.evseId)
        assertEquals(31.2, stored.energyKwh)
        assertEquals(Instant.parse("2026-11-02T09:00:00Z"), stored.sessionStart)
        assertEquals(TokenType.EMAID, stored.token!!.type)
        assertEquals(PricedBy.HUB, stored.pricedBy)
        val total = stored.ocpi!!["total_cost"]!!.jsonObject
        assertEquals("312.0", total["before_taxes"]!!.jsonPrimitive.content)
        assertTrue("excl_vat" !in total)
    }

    @Test
    fun `a store-and-forward replay is acknowledged and kept once`() {
        handler.handle(cdr())
        assertEquals(1000, handler.handle(cdr()).statusCode)
        assertEquals(1, sink.received.size)
    }

    @Test
    fun `a price-not-significant remark marks the CDR as needing our own pricing`() {
        handler.handle(cdr(remark = "[?] n/a"))
        assertEquals(PricedBy.NOBODY, sink.received.single().pricedBy)
    }

    @Test
    fun `a failing downstream is still a success for the IOP, the CDR is parked`() {
        sink.failing = true
        assertEquals(1000, handler.handle(cdr()).statusCode)
        assertEquals(0, sink.received.size)
    }

    @Test
    fun `garbage and a CDR without an id are 2001`() {
        assertEquals(2001, handler.handle("<xml/>").statusCode)
        assertEquals(2001, handler.handle("""{"session_id":"x"}""").statusCode)
    }

    @Test
    fun `without a session id the key falls back to the CPO's CDR identity`() {
        handler.handle("""{"country_code":"FR","party_id":"ABC","id":"CDR-9"}""")
        assertEquals("cdr:FR-ABC-CDR-9", sink.received.single().hubSessionId)
    }
}
