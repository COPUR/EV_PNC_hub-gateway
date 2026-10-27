package com.evpnc.hub.hubject

import com.evpnc.hub.core.AuthChannel
import com.evpnc.hub.core.AuthorizationPolicy
import com.evpnc.hub.core.AuthorizeDecision
import com.evpnc.hub.core.AuthorizeRequest
import com.evpnc.hub.core.DecisionOutcome
import com.evpnc.hub.core.DecisionReason
import com.evpnc.hub.core.TokenRef
import com.evpnc.hub.core.TokenType
import com.evpnc.hub.core.profile.ProfileDocument
import com.evpnc.hub.core.resilience.GuardedPolicy
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubjectAuthorizeHandlerTest {
    private val session = "b2688855-7f00-0002-6d8e-48d883f6abb6"
    private val seen = mutableListOf<AuthorizeRequest>()
    private var decision: () -> AuthorizeDecision = { AuthorizeDecision.Allowed() }

    private fun handler(profile: HubjectProfile = HubjectProfile.load(), margin: Duration = Duration.ofSeconds(2)) =
        HubjectAuthorizeHandler(
            providerId = "TRXYZ",
            policy = GuardedPolicy(AuthorizationPolicy { seen += it; decision() }),
            profile = profile,
            statusMapping = AuthorizeStatusMapping.load(),
            clock = Clock.systemUTC(),
            safetyMargin = margin,
        )

    private fun rfid(uid: String = "04A2B3C4D5E6F7") = AuthorizeStartRequest(
        operatorId = "TR*ZZZ",
        identification = OicpIdentification(rfid = OicpRfid(uid = uid, rfid = "mifareFamily")),
        sessionId = session,
        evseId = "TR*ZZZ*E0001*1",
        cpoPartnerSessionId = "cpo-1",
    )

    @Test
    fun `an allowed RFID start is Authorized with success, our ProviderID and the session ids echoed`() {
        val response = handler().handle(rfid())
        assertEquals("Authorized", response.authorizationStatus)
        assertEquals("000", response.statusCode.code)
        assertEquals("Success", response.statusCode.description)
        assertEquals("TRXYZ", response.providerId)
        assertEquals(session, response.sessionId)
        assertEquals("cpo-1", response.cpoPartnerSessionId)
        assertNull(response.stopIdentifications)
    }

    @Test
    fun `the policy sees a canonical request, not OICP`() {
        handler().handle(rfid())
        val request = seen.single()
        assertEquals(AuthChannel.RFID, request.channel)
        assertEquals(TokenRef("04A2B3C4D5E6F7", TokenType.RFID), request.token)
        assertEquals("TR*ZZZ*E0001*1", request.evseId)
        assertEquals("TR*ZZZ", request.operatorId)
        assertEquals(session, request.hubSessionId)
    }

    @Test
    fun `a refusal names the first reason in AdditionalInfo and uses the mapped code`() {
        decision = { AuthorizeDecision.Denied(DecisionOutcome.BLOCKED, listOf(DecisionReason.OPERATOR_BLOCKED, DecisionReason.PRICE_OVER_THRESHOLD)) }
        val response = handler().handle(rfid())
        assertEquals("NotAuthorized", response.authorizationStatus)
        assertEquals("210", response.statusCode.code)
        assertEquals("OPERATOR_BLOCKED, PRICE_OVER_THRESHOLD", response.statusCode.additionalInfo)
    }

    @Test
    fun `an unknown card is 102, an unknown QR code 101, an unknown Plug and Charge contract 105`() {
        decision = { AuthorizeDecision.Denied(DecisionOutcome.NOT_ALLOWED, listOf(DecisionReason.TOKEN_UNKNOWN)) }
        val h = handler()
        assertEquals("102", h.handle(rfid()).statusCode.code)
        val qr = rfid().copy(identification = OicpIdentification(qrCode = OicpQrCode("DE*8EO*Aet5e4*3", pin = "1234")))
        assertEquals("101", h.handle(qr).statusCode.code)
        val plc = rfid().copy(identification = OicpIdentification(plugAndCharge = OicpEvco("DE8EOCAET5E4AB3")))
        assertEquals("105", h.handle(plc).statusCode.code)
    }

    @Test
    fun `a QR code PIN reaches the policy as a secret and never appears in the answer`() {
        val qr = rfid().copy(identification = OicpIdentification(qrCode = OicpQrCode("DE*8EO*Aet5e4*3", pin = "98765")))
        val response = handler().handle(qr)
        val request = seen.single()
        assertEquals(AuthChannel.QR_CODE, request.channel)
        assertEquals("98765", request.pin!!.reveal())
        assertFalse(request.toString().contains("98765"))
        assertFalse(OicpJson.json.encodeToString(AuthorizeStartResponse.serializer(), response).contains("98765"))
    }

    @Test
    fun `a Plug and Charge identification becomes an EMAID token`() {
        val plc = rfid().copy(identification = OicpIdentification(plugAndCharge = OicpEvco("DE8EOCAET5E4AB3")))
        handler().handle(plc)
        assertEquals(TokenRef("DE8EOCAET5E4AB3", TokenType.EMAID, "DE8EOCAET5E4AB3"), seen.single().token)
        assertEquals(AuthChannel.PLUG_AND_CHARGE, seen.single().channel)
    }

    @Test
    fun `stop identifications from the decision go back to Hubject`() {
        decision = { AuthorizeDecision.Allowed(listOf(TokenRef("04A2B3C4D5E6F7", TokenType.RFID))) }
        val response = handler().handle(rfid())
        assertEquals("04A2B3C4D5E6F7", response.stopIdentifications!!.single().rfidMifareFamily!!.uid)
    }

    @Test
    fun `a UID that breaks the contract pattern is 102 and the policy is not asked`() {
        val response = handler().handle(rfid(uid = "not-hex"))
        assertEquals("NotAuthorized", response.authorizationStatus)
        assertEquals("102", response.statusCode.code)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `zero or two identification variants are a data error 022`() {
        val none = rfid().copy(identification = OicpIdentification())
        val two = rfid().copy(identification = OicpIdentification(rfid = OicpRfid("04A2B3C4D5E6F7", "nfc"), remote = OicpEvco("DE8EOCAET5E4AB3")))
        listOf(none, two).forEach {
            val response = handler().handle(it)
            assertEquals("022", response.statusCode.code)
            assertEquals("NotAuthorized", response.authorizationStatus)
        }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a SessionID that is not a GUID is 022`() {
        assertEquals("022", handler().handle(rfid().copy(sessionId = "x")).statusCode.code)
    }

    @Test
    fun `a failing policy is NotAuthorized with 021, not an error`() {
        decision = { error("policy engine down") }
        val response = handler().handle(rfid())
        assertEquals("NotAuthorized", response.authorizationStatus)
        assertEquals("021", response.statusCode.code)
        assertFalse(response.statusCode.additionalInfo.orEmpty().contains("down"), "internal detail must not leak to the hub")
    }

    @Test
    fun `a policy that misses the budget gets 021 inside the hub's window`() {
        val shortWindow = HubjectProfile(
            ProfileDocument.parse(
                "short",
                HubjectProfile::class.java.classLoader.getResource("profiles/${HubjectProfile.FILE}")!!.readText()
                    .replace("authorize_start_response_s: {value: 10", "authorize_start_response_s: {value: 1"),
            ),
        )
        val never = CountDownLatch(1)
        decision = { never.await(); AuthorizeDecision.Allowed() }
        val started = System.nanoTime()
        val response = handler(shortWindow, margin = Duration.ofMillis(800)).handle(rfid())
        val tookMs = (System.nanoTime() - started) / 1_000_000
        never.countDown()
        assertEquals("021", response.statusCode.code)
        assertEquals("NotAuthorized", response.authorizationStatus)
        assertTrue(tookMs < 1_000, "answered after $tookMs ms; the hub's window is 1000 ms")
    }

    @Test
    fun `AdditionalInfo is cut to the contract bound`() {
        val long = OicpStatusCode("021", "System error", "x".repeat(5000))
        assertEquals(5000, long.additionalInfo!!.length)
        assertEquals(1000, HubjectAuthorizeHandler.statusCode(OicpStatus.SYSTEM_ERROR, "x".repeat(5000)).additionalInfo!!.length)
    }

    @Test
    fun `the safety margin must be shorter than the hub's window`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            handler(margin = Duration.ofSeconds(10))
        }
    }
}
