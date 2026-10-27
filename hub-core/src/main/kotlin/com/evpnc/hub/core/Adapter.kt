package com.evpnc.hub.core

import com.evpnc.hub.core.profile.ProfileDocument

interface HubAdapter {
    val hub: Hub
    val capabilities: Set<HubCapability>
    val profile: ProfileDocument
}

class HubRegistry(adapters: List<HubAdapter>) {
    private val byHub: Map<Hub, HubAdapter>

    init {
        val duplicates = adapters.groupBy { it.hub }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Two adapters for the same hub: $duplicates" }
        byHub = adapters.associateBy { it.hub }
    }

    val hubs: Set<Hub> get() = byHub.keys

    fun adapter(hub: Hub): HubAdapter =
        byHub[hub] ?: throw HubException(hub, FailureClass.CONFIGURATION, null, "no adapter registered")

    inline fun <reified T> require(hub: Hub): T {
        val adapter = adapter(hub)
        return adapter as? T
            ?: throw HubException(hub, FailureClass.CONFIGURATION, null, "adapter does not implement ${T::class.simpleName}")
    }

    fun supports(hub: Hub, capability: HubCapability): Boolean = byHub[hub]?.capabilities?.contains(capability) == true

    /** Capability matrix for docs and health endpoints. */
    fun matrix(): Map<Hub, Set<HubCapability>> = byHub.mapValues { it.value.capabilities }
}
