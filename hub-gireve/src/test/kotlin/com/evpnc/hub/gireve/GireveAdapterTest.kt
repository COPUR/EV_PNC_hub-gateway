package com.evpnc.hub.gireve

import com.evpnc.hub.core.AuthorizationPolicy
import com.evpnc.hub.core.AuthorizeDecision
import com.evpnc.hub.core.FakeTransport
import com.evpnc.hub.core.HubAdapter
import com.evpnc.hub.core.HubAdapterContract
import com.evpnc.hub.core.HubCapability
import com.evpnc.hub.core.MutableClock
import com.evpnc.hub.core.RecordingSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GireveAdapterTest : HubAdapterContract() {
    private val adapter = GireveAdapter(config, AuthorizationPolicy { AuthorizeDecision.Allowed() }, RecordingSink(), FakeTransport(), MutableClock())

    override fun adapter(): HubAdapter = adapter

    @Test
    fun `it claims only what it has built`() {
        assertEquals(
            setOf(
                HubCapability.ONLINE_AUTHORIZE, HubCapability.REMOTE_START, HubCapability.REMOTE_STOP,
                HubCapability.CDR_RECEIVE, HubCapability.TOKEN_PUSH,
            ),
            adapter.capabilities,
        )
        assertFalse(HubCapability.PLUG_AND_CHARGE_CONTRACTS in adapter.capabilities)
        assertFalse(HubCapability.LOCATION_PULL in adapter.capabilities)
    }

    @Test
    fun `authorize works end to end through the adapter`() {
        val response = adapter.authorize("04A2B3C4D5E6F7", "RFID", """{"location_id":"LOC1"}""")
        assertEquals(1000, response.statusCode)
    }
}
