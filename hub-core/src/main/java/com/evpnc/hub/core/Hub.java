package com.evpnc.hub.core;

/** The roaming hubs the platform can talk to. One adapter module per entry (ADR-0018). */
public enum Hub {
    HUBJECT("hubject"),
    GIREVE("gireve"),
    E_CLEARING("e-clearing");

    private final String key;

    Hub(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
