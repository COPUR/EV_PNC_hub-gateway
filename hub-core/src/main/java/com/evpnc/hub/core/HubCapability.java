package com.evpnc.hub.core;

/** What an adapter really implements. The registry reports this so a gap is visible, not discovered in production. */
public enum HubCapability {
    ONLINE_AUTHORIZE,
    REMOTE_START,
    REMOTE_STOP,
    CDR_RECEIVE,
    CDR_RECONCILE,
    LOCATION_PULL,
    STATUS_PULL,
    TARIFF_SYNC,
    TOKEN_PUSH,
    PLUG_AND_CHARGE_CONTRACTS
}
