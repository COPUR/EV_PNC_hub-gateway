package com.evpnc.hub.core

import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HubProfileTest {
    private val p = HubProfile.fromContracts("gireve-ocpi-2.2.1.profile.yaml")

    @Test
    fun `the pinned GIREVE profile loads into typed configuration`() {
        assertEquals("gireve-ocpi-2.2.1-emsp", p.name)
        assertEquals("2.2.1", p.baseVersion)
        assertEquals(TokenEncoding.BASE64, p.tokenEncoding)
        assertEquals(setOf("Register-FromIOP", "Unregister-FromIOP"), p.registrationImplemented)
        assertEquals(setOf("START_SESSION", "STOP_SESSION"), p.commandsSupported)
        assertTrue(p.startSessionEvseUidRequired)
        assertEquals(100, p.pullMaxPerPage)
        assertEquals("fact", p.confidence)
        assertEquals(setOf("B2B Regular"), p.tariffTypesForwarded)
    }

    @Test
    fun `the hub party id depends on the environment`() {
        assertEquals("FR107", p.hubPartyId(Environment.PRE_PRODUCTION))
        assertEquals("FR007", p.hubPartyId(Environment.PRODUCTION))
    }

    @Test
    fun `supported modules and commands come from the profile`() {
        assertTrue(p.supportsModule("tokens") && p.supportsModule("cdrs"))
        assertFalse(p.supportsModule("chargingprofiles"))
        assertTrue(p.supportsCommand("START_SESSION"))
        assertFalse(p.supportsCommand("RESERVE_NOW"))
    }

    @Test
    fun `routing headers are not required on credentials and versions but tracing ids always are`() {
        val locations = p.requiredHeaders("locations")
        assertEquals(6, locations.size)
        assertTrue("OCPI-to-country-code" in locations && "X-Request-ID" in locations && "X-Correlation-ID" in locations)
        for (module in listOf("credentials", "versions")) assertEquals(listOf("X-Request-ID", "X-Correlation-ID"), p.requiredHeaders(module))
    }

    @Test
    fun `tokens are sent Base64 encoded`() {
        assertEquals("Token YWJjMTIz", p.authorizationHeader("abc123"))
    }

    @Test
    fun `page size never exceeds the hub limit`() {
        assertEquals(100, p.pageLimit(null))
        assertEquals(100, p.pageLimit(5000))
        assertEquals(10, p.pageLimit(10))
        assertEquals(1, p.pageLimit(0))
    }

    @Test
    fun `a pull range is limited to a calendar month and the planner window is shorter than any month`() {
        val from = ZonedDateTime.parse("2026-02-01T00:00:00Z")
        assertTrue(p.withinPullRange(from, ZonedDateTime.parse("2026-03-01T00:00:00Z")))
        assertFalse(p.withinPullRange(from, ZonedDateTime.parse("2026-03-02T00:00:00Z")))
        assertEquals(28, p.safePullWindow().toDays())
    }

    @Test
    fun `RFID uids are upper-case hex of four or seven bytes`() {
        assertEquals(emptyList(), p.rfidUidProblems("04A1B2C3"))
        assertEquals(emptyList(), p.rfidUidProblems("04A1B2C3D4E5F6"))
        assertEquals(listOf("uid must be upper-case hex"), p.rfidUidProblems("04a1b2c3"))
        assertEquals(listOf("uid must be 4 or 7 bytes"), p.rfidUidProblems("04A1B2C3D4"))
        assertEquals(listOf("uid is not hexadecimal"), p.rfidUidProblems("04A1B2CZ"))
    }

    @Test
    fun `the CDR remark says who priced it`() {
        assertEquals(PricedBy.HUB, p.pricedBy("[*] priced by the hub"))
        assertEquals(PricedBy.NOT_SIGNIFICANT, p.pricedBy("[?] price is not significant"))
        assertEquals(PricedBy.CPO, p.pricedBy("thanks for charging"))
        assertEquals(PricedBy.CPO, p.pricedBy(null))
    }

    @Test
    fun `a missing key fails the load and names its path`() {
        val yaml = pinnedYaml().replace("pull_limits: {max_per_page: 100, max_date_range: \"1 month\"}", "pull_limits: {max_date_range: \"1 month\"}")
        val e = assertFailsWith<ProfileError> { HubProfile.parse(yaml, "test.yaml") }
        assertEquals("test.yaml: tariffs.pull_limits.max_per_page must be a number", e.message)
    }

    @Test
    fun `an unreadable duration or encoding is refused`() {
        val badRange = pinnedYaml().replace("\"1 month\"", "\"a while\"")
        assertTrue(assertFailsWith<ProfileError> { HubProfile.parse(badRange) }.message!!.contains("cannot be read as a duration"))
        val badEncoding = pinnedYaml().replace("authorization_token_encoding: base64", "authorization_token_encoding: rot13")
        assertTrue(assertFailsWith<ProfileError> { HubProfile.parse(badEncoding) }.message!!.contains("must be base64 or plain"))
    }

    @Test
    fun `broken YAML and a non-mapping are refused`() {
        assertFailsWith<ProfileError> { HubProfile.parse("a: [unclosed") }
        assertFailsWith<ProfileError> { HubProfile.parse("- just\n- a list") }
    }

    private fun pinnedYaml() = HubProfile.defaultContractsDir().resolve("profiles/gireve-ocpi-2.2.1.profile.yaml").toFile().readText()
}
