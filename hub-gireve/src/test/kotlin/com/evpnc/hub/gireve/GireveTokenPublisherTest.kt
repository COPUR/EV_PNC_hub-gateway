package com.evpnc.hub.gireve

import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.ocpi221.Ocpi221Compat
import com.evpnc.hub.core.ocpi221.Ocpi221Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GireveTokenPublisherTest {
    private val publisher = GireveTokenPublisher(config, GireveProfile.load(), Ocpi221Compat(Hub.GIREVE, Ocpi221Config("FR", "107")))

    private fun token(type: String, uid: String) = json(
        """{"country_code":"TR","party_id":"ZZZ","uid":"$uid","type":"$type","contract_id":"C1","issuer":"EV_PNC","valid":true,"whitelist":"NEVER","last_updated":"2026-11-02T09:00:00Z"}""",
    )

    @Test
    fun `an RFID token is pushed with PUT, the UID normalised, to our own party path`() {
        val request = publisher.putRequest(token("RFID", "04a2b3c4d5e6f7"), "r1", "c1")!!
        assertEquals("PUT", request.method)
        assertEquals("/ocpi/2.2.1/tokens/TR/ZZZ/04A2B3C4D5E6F7", request.path)
        assertTrue("\"uid\":\"04A2B3C4D5E6F7\"" in request.body!!)
        assertEquals("107", request.headers["OCPI-to-party-id"])
    }

    @Test
    fun `Plug and Charge tokens are never pushed, they are authorised in real time`() {
        assertNull(publisher.putRequest(token("EMAID", "TRZZZC00000001X"), "r1", "c1"))
    }

    @Test
    fun `an RFID token with a malformed UID is not pushed`() {
        assertNull(publisher.putRequest(token("RFID", "zz"), "r1", "c1"))
    }

    @Test
    fun `app tokens keep their uid`() {
        val request = publisher.putRequest(token("APP_USER", "app-user-1"), "r1", "c1")!!
        assertEquals("/ocpi/2.2.1/tokens/TR/ZZZ/app-user-1", request.path)
    }
}
