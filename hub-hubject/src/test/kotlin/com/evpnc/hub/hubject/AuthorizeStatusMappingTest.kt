package com.evpnc.hub.hubject

import com.evpnc.hub.core.AuthChannel
import com.evpnc.hub.core.DecisionReason
import com.evpnc.hub.core.profile.ProfileDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthorizeStatusMappingTest {
    private val mapping = AuthorizeStatusMapping.load()

    @Test
    fun `an unknown RFID is 102 and an unknown QR code is 101, per channel`() {
        assertEquals(OicpStatus.RFID_INVALID_UID, mapping.forReason(DecisionReason.TOKEN_UNKNOWN, AuthChannel.RFID))
        assertEquals(OicpStatus.QR_INVALID_CREDENTIALS, mapping.forReason(DecisionReason.TOKEN_UNKNOWN, AuthChannel.QR_CODE))
        assertEquals(OicpStatus.PLC_INVALID_EVCO_ID, mapping.forReason(DecisionReason.TOKEN_UNKNOWN, AuthChannel.PLUG_AND_CHARGE))
    }

    @Test
    fun `policy refusals map to no valid contract, a revoked grant to locked`() {
        listOf(DecisionReason.NO_GRANT, DecisionReason.OPERATOR_BLOCKED, DecisionReason.OPERATOR_NOT_ALLOWED, DecisionReason.PRICE_OVER_THRESHOLD)
            .forEach { assertEquals(OicpStatus.NO_VALID_CONTRACT, mapping.forReason(it, AuthChannel.RFID), it.name) }
        assertEquals(OicpStatus.EVCO_ID_LOCKED, mapping.forReason(DecisionReason.GRANT_REVOKED, AuthChannel.QR_CODE))
    }

    @Test
    fun `every platform reason has an answer on every channel`() {
        for (reason in DecisionReason.entries) for (channel in AuthChannel.entries) {
            mapping.forReason(reason, channel)
        }
    }

    @Test
    fun `fallbacks are 021 for a missed budget or a failed policy and 022 for a malformed request`() {
        assertEquals(OicpStatus.SYSTEM_ERROR, mapping.policyTimeout)
        assertEquals(OicpStatus.SYSTEM_ERROR, mapping.policyFailed)
        assertEquals(OicpStatus.DATA_ERROR, mapping.malformedRequest)
        assertEquals(OicpStatus.SUCCESS, mapping.allowed)
    }

    @Test
    fun `a table that names a code outside the OICP list is refused at start-up`() {
        val bad = ProfileDocument.parse(
            "bad",
            """
            allowed: "000"
            by_reason: {OTHER: "999"}
            fallback: {policy_timeout: "021", policy_failed: "021", malformed_request: "022"}
            """.trimIndent(),
        )
        assertFailsWith<IllegalArgumentException> { AuthorizeStatusMapping(bad) }
    }
}
