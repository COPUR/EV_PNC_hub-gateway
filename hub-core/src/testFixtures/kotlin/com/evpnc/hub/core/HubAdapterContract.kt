package com.evpnc.hub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What every hub adapter must satisfy, whatever the hub. Subclass it in the adapter module and provide the adapter;
 * a new hub (e-clearing.net in phase 5) joins the framework by passing this class first.
 */
abstract class HubAdapterContract {
    protected abstract fun adapter(): HubAdapter

    /** Capabilities that must be backed by a port the adapter implements. */
    private val portBackedCapabilities = mapOf(
        HubCapability.REMOTE_START to RemoteSessionPort::class.java,
        HubCapability.REMOTE_STOP to RemoteSessionPort::class.java,
    )

    @Test
    fun `a capability that needs a port is backed by that port`() {
        val adapter = adapter()
        portBackedCapabilities.forEach { (capability, port) ->
            if (capability in adapter.capabilities) {
                assertTrue(port.isInstance(adapter), "${adapter.hub} claims $capability but does not implement ${port.simpleName}")
            }
        }
    }

    @Test
    fun `the adapter can be registered and found by its hub`() {
        val adapter = adapter()
        val registry = HubRegistry(listOf(adapter))
        assertEquals(adapter, registry.adapter(adapter.hub))
        assertEquals(adapter.capabilities, registry.matrix().getValue(adapter.hub))
    }

    @Test
    fun `the adapter declares at least one capability and loads its profile`() {
        val adapter = adapter()
        assertTrue(adapter.capabilities.isNotEmpty(), "no capability declared")
        assertTrue(adapter.profile.name.isNotBlank(), "profile has no name")
    }
}
