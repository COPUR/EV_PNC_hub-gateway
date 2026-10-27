package com.evpnc.hub.core.resilience

import com.evpnc.hub.core.CdrSink
import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.HubCdr
import com.evpnc.hub.core.hubCdr
import kotlin.test.Test
import kotlin.test.assertEquals

class CdrIntakeTest {
    private val delivered = mutableListOf<HubCdr>()
    private var failing = false
    private val intake = CdrIntake(
        CdrSink { if (failing) error("payments down") else delivered += it },
        InMemoryIdempotencyStore(),
    )

    @Test
    fun `first delivery is stored, a resend is a duplicate`() {
        assertEquals(IntakeResult.STORED, intake.receive(hubCdr()))
        assertEquals(IntakeResult.DUPLICATE, intake.receive(hubCdr()))
        assertEquals(1, delivered.size)
    }

    @Test
    fun `the same session id on two hubs is two records`() {
        assertEquals(IntakeResult.STORED, intake.receive(hubCdr(Hub.HUBJECT, "S1")))
        assertEquals(IntakeResult.STORED, intake.receive(hubCdr(Hub.GIREVE, "S1")))
    }

    @Test
    fun `a failing sink parks the record, replay delivers it once the sink is back`() {
        failing = true
        assertEquals(IntakeResult.PARKED, intake.receive(hubCdr(sessionId = "S2")))
        assertEquals(1, intake.parkedCount())
        assertEquals(0, intake.replay(), "still failing")
        assertEquals(1, intake.parkedCount())
        failing = false
        assertEquals(1, intake.replay())
        assertEquals(0, intake.parkedCount())
        assertEquals(listOf("S2"), delivered.map { it.hubSessionId })
    }

    @Test
    fun `a parked record that is resent by the hub is not stored twice`() {
        failing = true
        intake.receive(hubCdr(sessionId = "S3"))
        failing = false
        assertEquals(IntakeResult.DUPLICATE, intake.receive(hubCdr(sessionId = "S3")))
        intake.replay()
        assertEquals(1, delivered.size)
    }
}
