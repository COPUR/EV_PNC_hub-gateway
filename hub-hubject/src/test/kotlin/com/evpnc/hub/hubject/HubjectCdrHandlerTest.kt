package com.evpnc.hub.hubject

import com.evpnc.hub.core.RecordingSink
import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.PricedBy
import com.evpnc.hub.core.resilience.CdrIntake
import com.evpnc.hub.core.resilience.InMemoryIdempotencyStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubjectCdrHandlerTest {
    private val sink = RecordingSink()
    private val handler = HubjectCdrHandler(CdrIntake(sink, InMemoryIdempotencyStore()))

    private fun cdr(sessionId: String = "b2688855-7f00-0002-6d8e-48d883f6abb6") = OicpChargeDetailRecord(
        sessionId = sessionId,
        evseId = "TR*ZZZ*E0001*1",
        identification = OicpIdentification(rfid = OicpRfid("04A2B3C4D5E6F7", "mifareFamily")),
        chargingStart = "2026-11-02T12:05:00+03:00",
        chargingEnd = "2026-11-02T12:35:00+03:00",
        sessionStart = "2026-11-02T12:00:00+03:00",
        sessionEnd = "2026-11-02T12:36:00+03:00",
        consumedEnergy = 31.2,
        meterValueStart = 100.0,
        meterValueEnd = 131.2,
        hubOperatorId = "DE*HUB",
    )

    @Test
    fun `a CDR is stored with +03_00 timestamps as instants and acknowledged with the session id`() {
        val ack = handler.handle(cdr())
        assertTrue(ack.result)
        assertEquals("000", ack.statusCode.code)
        assertEquals("b2688855-7f00-0002-6d8e-48d883f6abb6", ack.sessionId)
        val stored = sink.received.single()
        assertEquals(Hub.HUBJECT, stored.hub)
        assertEquals(Instant.parse("2026-11-02T09:00:00Z"), stored.sessionStart)
        assertEquals(Instant.parse("2026-11-02T09:36:00Z"), stored.sessionEnd)
        assertEquals(31.2, stored.energyKwh)
        assertEquals(PricedBy.NOBODY, stored.pricedBy, "OICP CDRs carry no price; payments prices them")
    }

    @Test
    fun `a resend is acknowledged positively and stored once`() {
        handler.handle(cdr())
        val ack = handler.handle(cdr())
        assertTrue(ack.result)
        assertEquals(1, sink.received.size)
        assertEquals("duplicate, already received", ack.statusCode.additionalInfo)
    }

    @Test
    fun `a failing downstream still gets a positive answer because Hubject never resends`() {
        sink.failing = true
        val ack = handler.handle(cdr("c2688855-7f00-0002-6d8e-48d883f6abb6"))
        assertTrue(ack.result)
        assertEquals("accepted, processing deferred", ack.statusCode.additionalInfo)
    }

    @Test
    fun `a CDR without a SessionID is the only thing refused`() {
        val ack = handler.handle(cdr(sessionId = " "))
        assertFalse(ack.result)
        assertEquals("022", ack.statusCode.code)
    }

    @Test
    fun `unreadable timestamps are kept as text under extensions, not lost`() {
        handler.handle(cdr().copy(sessionStart = "yesterday-ish"))
        val stored = sink.received.single()
        assertNull(stored.sessionStart)
        val oicp = stored.extensions["oicp"]!!.toString()
        assertTrue("yesterday-ish" in oicp, oicp)
    }

    @Test
    fun `signed metering and the calibration info travel in the oicp extension`() {
        val record = cdr().copy(
            signedMeteringValues = listOf(OicpSignedMeteringValue("QUJD", "Start"), OicpSignedMeteringValue("REVG", "End")),
            calibration = OicpCalibrationLawVerificationInfo(certificateId = "CAL-1"),
        )
        handler.handle(record)
        val oicp = sink.received.single().extensions["oicp"]!!.toString()
        assertTrue("QUJD" in oicp && "REVG" in oicp && "CAL-1" in oicp, oicp)
        assertNotNull(sink.received.single().token)
    }
}
