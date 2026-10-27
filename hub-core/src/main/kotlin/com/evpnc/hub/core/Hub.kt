package com.evpnc.hub.core

/** The roaming hubs the platform can talk to. One adapter module per entry (ADR-0018). */
enum class Hub(val key: String) {
    HUBJECT("hubject"),
    GIREVE("gireve"),
    E_CLEARING("e-clearing"),
}

/** What an adapter really implements. The registry reports this so a gap is visible, not discovered in production. */
enum class HubCapability {
    ONLINE_AUTHORIZE,
    REMOTE_START,
    REMOTE_STOP,
    CDR_RECEIVE,
    CDR_RECONCILE,
    LOCATION_PULL,
    STATUS_PULL,
    TARIFF_SYNC,
    TOKEN_PUSH,
    PLUG_AND_CHARGE_CONTRACTS,
}
