package com.evpnc.hub.gireve

import com.evpnc.hub.core.AuthorizationPolicy
import com.evpnc.hub.core.AuthorizeDecision
import com.evpnc.hub.core.AuthorizeRequest
import com.evpnc.hub.core.DecisionOutcome
import com.evpnc.hub.core.DecisionReason
import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.MutableClock
import com.evpnc.hub.core.TokenRef
import com.evpnc.hub.core.TokenType
import com.evpnc.hub.core.ocpi221.Ocpi221Compat
import com.evpnc.hub.core.ocpi221.Ocpi221Config
import com.evpnc.hub.core.resilience.GuardedPolicy
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GireveAuthorizeHandlerTest {
    private val seen = mutableListOf<AuthorizeRequest>()
    private var decision: () -> AuthorizeDecision = { AuthorizeDecision.Allowed(authorizationReference = "AUTH-1") }
    private val locations = """{"location_id":"LOC1","evse_uids":["E1"]}"""

    private fun handler(budget: Duration = Duration.ofSeconds(2)) = GireveAuthorizeHandler(
        config = config.copy(authorizeBudget = budget),
        profile = GireveProfile.load(),
        compat = Ocpi221Compat(Hub.GIREVE, Ocpi221Config("FR", "107")),
        policy = GuardedPolicy(AuthorizationPolicy { seen += it; decision() }),
        clock = MutableClock(),
    )

    private fun data(response: com.evpnc.hub.gireve.OcpiResponse) = response.data!!.jsonObject

    @Test
    fun `an allowed token gets AuthorizationInfo with the token, the location and our reference`() {
        val response = handler().handle("04A2B3C4D5E6F7", "RFID", locations)
        assertEquals(1000, response.statusCode)
        val info = data(response)
        assertEquals("ALLOWED", info["allowed"]!!.jsonPrimitive.content)
        assertEquals("04A2B3C4D5E6F7", info["token"]!!.jsonObject["uid"]!!.jsonPrimitive.content)
        assertEquals("TR", info["token"]!!.jsonObject["country_code"]!!.jsonPrimitive.content)
        assertEquals("LOC1", info["location"]!!.jsonObject["location_id"]!!.jsonPrimitive.content)
        assertEquals("AUTH-1", info["authorization_reference"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the policy sees a canonical token authorize request`() {
        handler().handle("04a2b3c4d5e6f7", "RFID", locations)
        val request = seen.single()
        assertEquals(Hub.GIREVE, request.hub)
        assertEquals(TokenRef("04A2B3C4D5E6F7", TokenType.RFID), request.token)
        assertEquals("E1", request.evseId)
        assertEquals("LOC1", request.operatorId)
    }

    @Test
    fun `a request without location references is 2002 and the policy is not asked`() {
        listOf(null, "", "{}", """{"evse_uids":["E1"]}""").forEach {
            assertEquals(2002, handler().handle("04A2B3C4D5E6F7", "RFID", it).statusCode, "body: $it")
        }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `an unknown token is 2004 with no data`() {
        decision = { AuthorizeDecision.Denied(DecisionOutcome.NOT_ALLOWED, listOf(DecisionReason.TOKEN_UNKNOWN)) }
        val response = handler().handle("04A2B3C4D5E6F7", "RFID", locations)
        assertEquals(2004, response.statusCode)
        assertNull(response.data)
    }

    @Test
    fun `a malformed RFID UID is unknown without asking the policy`() {
        assertEquals(2004, handler().handle("not-hex", "RFID", locations).statusCode)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a known but refused token is AuthorizationInfo with the refusal and no location`() {
        mapOf(
            DecisionOutcome.BLOCKED to "BLOCKED", DecisionOutcome.NO_CREDIT to "NO_CREDIT", DecisionOutcome.NOT_ALLOWED to "NOT_ALLOWED",
        ).forEach { (outcome, expected) ->
            decision = { AuthorizeDecision.Denied(outcome, listOf(DecisionReason.OPERATOR_BLOCKED)) }
            val response = handler().handle("04A2B3C4D5E6F7", "RFID", locations)
            assertEquals(1000, response.statusCode)
            assertEquals(expected, data(response)["allowed"]!!.jsonPrimitive.content)
            assertNull(data(response)["location"])
        }
    }

    @Test
    fun `a Plug and Charge token arrives as OTHER with an eMAID and reaches the policy as EMAID`() {
        handler().handle("TRZZZC00000001X", "OTHER", locations)
        assertEquals(TokenRef("TRZZZC00000001X", TokenType.EMAID, "TRZZZC00000001X"), seen.single().token)
    }

    @Test
    fun `an EMAID token goes back to the IOP as OTHER with uid and contract_id equal to the eMAID`() {
        val response = handler().handle("TRZZZC00000001X", "OTHER", locations)
        val token: JsonObject = data(response)["token"]!!.jsonObject
        assertEquals("OTHER", token["type"]!!.jsonPrimitive.content)
        assertEquals("TRZZZC00000001X", token["uid"]!!.jsonPrimitive.content)
        assertEquals("TRZZZC00000001X", token["contract_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a policy that misses the budget is a server error, not a guess`() {
        val never = CountDownLatch(1)
        decision = { never.await(); AuthorizeDecision.Allowed() }
        val response = handler(Duration.ofMillis(100)).handle("04A2B3C4D5E6F7", "RFID", locations)
        never.countDown()
        assertEquals(3000, response.statusCode)
        assertNull(response.data)
    }

    @Test
    fun `the answer envelope serialises with status_code and timestamp`() {
        val text = handler().handle("04A2B3C4D5E6F7", "RFID", locations).toJson()
        assertTrue("\"status_code\":1000" in text && "\"timestamp\"" in text, text)
        assertNotNull(OcpiResponse.parse(text))
    }
}
