package com.evpnc.hub.hubject

import com.evpnc.hub.core.FakeTransport
import com.evpnc.hub.core.FailureClass
import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.HubException
import com.evpnc.hub.core.RemoteRejection
import com.evpnc.hub.core.RemoteResult
import com.evpnc.hub.core.RemoteStart
import com.evpnc.hub.core.RemoteStop
import com.evpnc.hub.core.TokenRef
import com.evpnc.hub.core.TokenType
import com.evpnc.hub.core.TransportTimeout
import com.evpnc.hub.core.resilience.Throttler
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HubjectRemoteClientTest {
    private val transport = FakeTransport()
    private val clock = newClock()
    private fun client(limits: Map<String, Int> = emptyMap()) = HubjectRemoteClient("TRXYZ", transport, Throttler(limits, clock))
    private val start = RemoteStart(Hub.HUBJECT, "req-1", TokenRef("TRXYZC00000001A", TokenType.APP_USER, "TRXYZC00000001A"), "TR*ZZZ*E0001*1")

    private fun ack(code: String, result: Boolean, session: String? = null) =
        """{"Result":$result,"StatusCode":{"Code":"$code"}${session?.let { ""","SessionID":"$it"""" } ?: ""}}"""

    @Test
    fun `remote start posts to our provider path, sends no SessionID and a remote identification`() {
        transport.answers(200, ack("000", true, "b2688855-7f00-0002-6d8e-48d883f6abb6"))
        val result = client().start(start)
        val request = transport.sent.single()
        assertEquals("POST", request.method)
        assertEquals("/charging/v21/providers/TRXYZ/authorize-remote/start", request.path)
        assertFalse("\"SessionID\"" in request.body!!, request.body)
        assertTrue("\"RemoteIdentification\":{\"EvcoID\":\"TRXYZC00000001A\"}" in request.body!!, request.body)
        assertTrue("\"EMPPartnerSessionID\":\"req-1\"" in request.body!!)
        assertEquals(RemoteResult.Accepted("b2688855-7f00-0002-6d8e-48d883f6abb6"), result)
    }

    @Test
    fun `remote stop sends the hub session, our provider and the EVSE`() {
        transport.answers(200, ack("000", true))
        client().stop(RemoteStop(Hub.HUBJECT, "b2688855-7f00-0002-6d8e-48d883f6abb6", "TR*ZZZ*E0001*1"))
        assertEquals("/charging/v21/providers/TRXYZ/authorize-remote/stop", transport.sent.single().path)
        assertTrue("\"SessionID\":\"b2688855-7f00-0002-6d8e-48d883f6abb6\"" in transport.sent.single().body!!)
    }

    @Test
    fun `failure codes map to driver-facing reasons`() {
        val expected = mapOf(
            "210" to RemoteRejection.NO_VALID_CONTRACT, "300" to RemoteRejection.PARTNER_UNKNOWN,
            "310" to RemoteRejection.PARTNER_UNREACHABLE, "501" to RemoteRejection.EVSE_UNREACHABLE,
            "510" to RemoteRejection.NO_VEHICLE_CONNECTED, "601" to RemoteRejection.EVSE_RESERVED,
            "602" to RemoteRejection.EVSE_IN_USE, "603" to RemoteRejection.EVSE_UNKNOWN,
            "604" to RemoteRejection.EVSE_NOT_ROAMABLE, "700" to RemoteRejection.EVSE_OUT_OF_SERVICE,
            "021" to RemoteRejection.OTHER,
        )
        expected.forEach { (code, reason) ->
            transport.answers(200, ack(code, false))
            val result = client().start(start)
            assertEquals(RemoteResult.Rejected(code, reason), result, code)
        }
    }

    @Test
    fun `a code outside the official list is a failure, not a crash`() {
        transport.answers(200, ack("777", false))
        assertEquals(RemoteResult.Rejected("777", RemoteRejection.OTHER), client().start(start))
    }

    @Test
    fun `017 is a configuration error that must not be retried`() {
        transport.answers(200, ack("017", false))
        val e = assertFailsWith<HubException> { client().start(start) }
        assertEquals(FailureClass.CONFIGURATION, e.failureClass)
    }

    @Test
    fun `a timeout leaves the state unknown instead of failing the start`() {
        transport.next = { throw TransportTimeout("65 s") }
        assertIs<RemoteResult.UnknownState>(client().start(start))
    }

    @Test
    fun `429 and 5xx are retryable, other statuses are not, garbage is not retried`() {
        transport.answers(429, "")
        assertEquals(FailureClass.RETRYABLE, assertFailsWith<HubException> { client().start(start) }.failureClass)
        transport.answers(503, "")
        assertEquals(FailureClass.RETRYABLE, assertFailsWith<HubException> { client().start(start) }.failureClass)
        transport.answers(400, "")
        assertEquals(FailureClass.NON_RETRYABLE, assertFailsWith<HubException> { client().start(start) }.failureClass)
        transport.answers(200, "<html>")
        assertEquals(FailureClass.NON_RETRYABLE, assertFailsWith<HubException> { client().start(start) }.failureClass)
    }

    @Test
    fun `calls over the Acceptable Use Policy limit are held back before reaching the hub`() {
        transport.answers(200, ack("000", true))
        val client = client(mapOf("AuthorizeRemoteStart" to 2))
        client.start(start)
        client.start(start)
        val e = assertFailsWith<HubException> { client.start(start) }
        assertEquals("THROTTLED", e.code)
        assertEquals(FailureClass.RETRYABLE, e.failureClass)
        assertEquals(2, transport.sent.size)
        clock.advance(Duration.ofSeconds(30))
        client.start(start)
        assertEquals(3, transport.sent.size)
    }
}
