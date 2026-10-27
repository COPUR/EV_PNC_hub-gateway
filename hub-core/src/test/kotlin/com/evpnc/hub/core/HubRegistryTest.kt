package com.evpnc.hub.core

import com.evpnc.hub.core.profile.ProfileDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HubRegistryTest {
    private class Fake(override val hub: Hub, override val capabilities: Set<HubCapability>) : HubAdapter {
        override val profile = ProfileDocument("fake", emptyMap())
    }

    private class Remote(hub: Hub) : HubAdapter by Fake(hub, setOf(HubCapability.REMOTE_START)), RemoteSessionPort {
        override fun start(command: RemoteStart) = RemoteResult.Accepted(null)

        override fun stop(command: RemoteStop) = RemoteResult.Accepted(null)
    }

    @Test
    fun `capability matrix shows what each hub implements`() {
        val registry = HubRegistry(listOf(Remote(Hub.HUBJECT), Fake(Hub.GIREVE, setOf(HubCapability.CDR_RECEIVE))))
        assertTrue(registry.supports(Hub.HUBJECT, HubCapability.REMOTE_START))
        assertEquals(false, registry.supports(Hub.GIREVE, HubCapability.REMOTE_START))
        assertEquals(setOf(Hub.HUBJECT, Hub.GIREVE), registry.matrix().keys)
    }

    @Test
    fun `an adapter is found by the port it implements`() {
        val registry = HubRegistry(listOf(Remote(Hub.HUBJECT), Fake(Hub.GIREVE, emptySet())))
        registry.require<RemoteSessionPort>(Hub.HUBJECT)
        val e = assertFailsWith<HubException> { registry.require<RemoteSessionPort>(Hub.GIREVE) }
        assertEquals(FailureClass.CONFIGURATION, e.failureClass)
    }

    @Test
    fun `a hub without an adapter and a duplicate adapter are configuration errors`() {
        assertFailsWith<HubException> { HubRegistry(emptyList()).adapter(Hub.E_CLEARING) }
        assertFailsWith<IllegalArgumentException> { HubRegistry(listOf(Fake(Hub.GIREVE, emptySet()), Fake(Hub.GIREVE, emptySet()))) }
    }
}
