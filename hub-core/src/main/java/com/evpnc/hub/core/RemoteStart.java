package com.evpnc.hub.core;

/**
 * @param locationId CPO's location id; GIREVE needs it, Hubject does not (null when absent)
 * @param connectorId null when absent
 */
public record RemoteStart(
        Hub hub, String requestId, TokenRef token, String evseId, String locationId, String connectorId) {

    public RemoteStart(Hub hub, String requestId, TokenRef token, String evseId) {
        this(hub, requestId, token, evseId, null, null);
    }
}
