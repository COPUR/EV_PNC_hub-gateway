package com.evpnc.hub.core.ocpi221

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OcpiHeadersTest {
    private val us = OcpiParty("TR", "ZZZ")
    private val hub = OcpiParty("FR", "107")

    @Test
    fun `routing headers are present on cdrs and tokens`() {
        val headers = OcpiHeaders.request("cdrs", us, hub, "secret", "r1", "c1")
        assertEquals("TR", headers["OCPI-from-country-code"])
        assertEquals("107", headers["OCPI-to-party-id"])
        assertEquals("r1", headers["X-Request-ID"])
        assertEquals("c1", headers["X-Correlation-ID"])
    }

    @Test
    fun `credentials and versions carry no routing headers but still the tracing ids`() {
        listOf("credentials", "versions").forEach { module ->
            val headers = OcpiHeaders.request(module, us, hub, "secret", "r1", "c1")
            assertFalse(headers.keys.any { it.startsWith("OCPI-") }, module)
            assertTrue("X-Request-ID" in headers)
        }
    }

    @Test
    fun `the token is Base64 encoded`() {
        val auth = OcpiHeaders.request("cdrs", us, hub, "secret", "r1", "c1")["Authorization"]!!
        assertEquals("Token " + Base64.getEncoder().encodeToString("secret".toByteArray()), auth)
    }

    @Test
    fun `an answer must echo both ids`() {
        val sent = mapOf("X-Request-ID" to "r1", "X-Correlation-ID" to "c1")
        assertEquals(emptyList(), OcpiHeaders.checkEcho(sent, mapOf("x-request-id" to "r1", "x-correlation-id" to "c1")))
        val problems = OcpiHeaders.checkEcho(sent, mapOf("X-Request-ID" to "other"))
        assertEquals(2, problems.size, problems.toString())
    }
}
