package com.evpnc.hub.hubject

import com.evpnc.hub.core.RecordingSink
import com.evpnc.hub.core.FakeTransport
import com.evpnc.hub.core.HubAdapter
import com.evpnc.hub.core.HubAdapterContract
import com.evpnc.hub.core.HubCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HubjectAdapterTest : HubAdapterContract() {
    private val sink = RecordingSink()
    private val transport = FakeTransport()

    private val adapter = HubjectAdapter("TRXYZ", Policies.allow, sink, transport, newClock())

    override fun adapter(): HubAdapter = adapter

    @Test
    fun `it claims only what it has built`() {
        assertEquals(
            setOf(HubCapability.ONLINE_AUTHORIZE, HubCapability.REMOTE_START, HubCapability.REMOTE_STOP, HubCapability.CDR_RECEIVE),
            adapter.capabilities,
        )
        assertFalse(HubCapability.PLUG_AND_CHARGE_CONTRACTS in adapter.capabilities)
    }

    @Test
    fun `authorize start works end to end on JSON text`() {
        val body = """{"OperatorID":"TR*ZZZ","SessionID":"b2688855-7f00-0002-6d8e-48d883f6abb6",
            "Identification":{"RFIDIdentification":{"UID":"04A2B3C4D5E6F7","RFID":"mifareFamily"}}}"""
        val answer = adapter.authorizeStart(body)
        assertTrue("\"AuthorizationStatus\":\"Authorized\"" in answer, answer)
        assertTrue("\"ProviderID\":\"TRXYZ\"" in answer, answer)
    }

    @Test
    fun `unparseable JSON is answered NotAuthorized 022, never an exception`() {
        val answer = adapter.authorizeStart("{not json")
        assertTrue("\"NotAuthorized\"" in answer && "\"022\"" in answer, answer)
        val cdr = adapter.chargeDetailRecord("[]")
        assertTrue("\"Result\":false" in cdr && "\"022\"" in cdr, cdr)
    }

    @Test
    fun `unknown fields from a newer OICP minor version are ignored`() {
        val body = """{"OperatorID":"TR*ZZZ","NewField":42,
            "Identification":{"RFIDIdentification":{"UID":"04A2B3C4D5E6F7","RFID":"mifareFamily","Surprise":true}}}"""
        assertTrue("\"Authorized\"" in adapter.authorizeStart(body))
    }

    @Test
    fun `a CDR posted as JSON text is stored once even if posted twice`() {
        val body = """{"SessionID":"b2688855-7f00-0002-6d8e-48d883f6abb6","EvseID":"TR*ZZZ*E0001*1",
            "Identification":{"RemoteIdentification":{"EvcoID":"TRXYZC00000001A"}},
            "ChargingStart":"2026-11-02T12:05:00+03:00","ChargingEnd":"2026-11-02T12:35:00+03:00",
            "SessionStart":"2026-11-02T12:00:00+03:00","SessionEnd":"2026-11-02T12:36:00+03:00","ConsumedEnergy":31.2}"""
        repeat(2) { assertTrue("\"Result\":true" in adapter.chargeDetailRecord(body)) }
        assertEquals(1, sink.received.size)
    }
}
