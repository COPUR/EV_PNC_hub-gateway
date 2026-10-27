package com.evpnc.hub.gireve

import com.evpnc.hub.core.FailureClass
import com.evpnc.hub.core.FakeTransport
import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.HubException
import com.evpnc.hub.core.MutableClock
import com.evpnc.hub.core.RemoteRejection
import com.evpnc.hub.core.RemoteResult
import com.evpnc.hub.core.RemoteStart
import com.evpnc.hub.core.RemoteStop
import com.evpnc.hub.core.TokenRef
import com.evpnc.hub.core.TokenType
import com.evpnc.hub.core.TransportTimeout
import com.evpnc.hub.core.ocpi221.Ocpi221Compat
import com.evpnc.hub.core.ocpi221.Ocpi221Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GireveCommandClientTest {
    private val transport = FakeTransport()
    private val client = GireveCommandClient(
        config, GireveProfile.load(), Ocpi221Compat(Hub.GIREVE, Ocpi221Config("FR", "107")), transport, MutableClock(),
    )
    private val start = RemoteStart(
        Hub.GIREVE, "req-1", TokenRef("TRZZZC00000001X", TokenType.EMAID, "TRZZZC00000001X"), evseId = "E1", locationId = "LOC1",
    )

    private fun envelope(code: Int, result: String? = null) =
        """{"status_code":$code,"status_message":"m"${result?.let { ""","data":{"result":"$it","timeout":30}""" } ?: ""}}"""

    @Test
    fun `START_SESSION goes to the commands endpoint with routing headers, evse_uid and an OTHER-typed eMAID token`() {
        transport.answers(200, envelope(1000, "ACCEPTED"))
        val result = client.start(start)
        val request = transport.sent.single()
        assertEquals("POST", request.method)
        assertEquals("/ocpi/2.2.1/commands/START_SESSION", request.path)
        assertEquals("FR", request.headers["OCPI-to-country-code"])
        assertEquals("107", request.headers["OCPI-to-party-id"])
        assertEquals("TR", request.headers["OCPI-from-country-code"])
        assertEquals("req-1", request.headers["X-Request-ID"])
        val body = kotlinx.serialization.json.Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("E1", body["evse_uid"]!!.jsonPrimitive.content)
        assertEquals("LOC1", body["location_id"]!!.jsonPrimitive.content)
        assertEquals("OTHER", body["token"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("https://emsp.example/ocpi/2.2.1/commands/START_SESSION/req-1", body["response_url"]!!.jsonPrimitive.content)
        assertEquals(RemoteResult.Accepted(null), result)
    }

    @Test
    fun `START_SESSION without a location id fails before any call`() {
        val e = assertFailsWith<HubException> { client.start(start.copy(locationId = null)) }
        assertEquals("LOCATION_REQUIRED", e.code)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `STOP_SESSION sends the hub session id`() {
        transport.answers(200, envelope(1000, "ACCEPTED"))
        client.stop(RemoteStop(Hub.GIREVE, "GIR-SES-1", "E1"))
        assertEquals("/ocpi/2.2.1/commands/STOP_SESSION", transport.sent.single().path)
        assertTrue("\"session_id\":\"GIR-SES-1\"" in transport.sent.single().body!!)
    }

    @Test
    fun `command results other than ACCEPTED are rejections`() {
        listOf("REJECTED", "NOT_SUPPORTED", "UNKNOWN_SESSION").forEach {
            transport.answers(200, envelope(1000, it))
            assertEquals(RemoteResult.Rejected(it, RemoteRejection.OTHER), client.start(start))
        }
    }

    @Test
    fun `hub status codes 4001, 4002 and 4003 map to unknown receiver, unknown state and not connected`() {
        transport.answers(200, envelope(4001))
        assertEquals(RemoteRejection.PARTNER_UNKNOWN, (client.start(start) as RemoteResult.Rejected).reason)
        transport.answers(200, envelope(4003))
        assertEquals(RemoteRejection.PARTNER_UNREACHABLE, (client.start(start) as RemoteResult.Rejected).reason)
        transport.answers(200, envelope(4002))
        assertIs<RemoteResult.UnknownState>(client.start(start))
    }

    @Test
    fun `a transport timeout leaves the state unknown`() {
        transport.next = { throw TransportTimeout("30 s") }
        assertIs<RemoteResult.UnknownState>(client.start(start))
    }

    @Test
    fun `425 and 429 are wait signals, 5xx and 3xxx retryable, other client errors are not`() {
        listOf(425, 429, 502).forEach {
            transport.answers(it, "")
            assertEquals(FailureClass.RETRYABLE, assertFailsWith<HubException> { client.start(start) }.failureClass, "$it")
        }
        transport.answers(200, envelope(3000))
        assertEquals(FailureClass.RETRYABLE, assertFailsWith<HubException> { client.start(start) }.failureClass)
        transport.answers(200, envelope(2001))
        assertEquals(FailureClass.NON_RETRYABLE, assertFailsWith<HubException> { client.start(start) }.failureClass)
        transport.answers(404, "")
        assertEquals(FailureClass.NON_RETRYABLE, assertFailsWith<HubException> { client.start(start) }.failureClass)
        transport.answers(200, "<html>")
        assertEquals(FailureClass.NON_RETRYABLE, assertFailsWith<HubException> { client.start(start) }.failureClass)
    }

    @Test
    fun `the hub token is Base64 encoded in Authorization and never in the body`() {
        transport.answers(200, envelope(1000, "ACCEPTED"))
        client.start(start)
        val request = transport.sent.single()
        assertTrue(request.headers.getValue("Authorization").startsWith("Token "))
        assertTrue("hub-secret" !in request.body!!)
    }
}
