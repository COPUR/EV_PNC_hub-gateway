package com.evpnc.hub.core;

@FunctionalInterface
public interface HubTransport {
    /** @throws TransportTimeout when the hub did not answer in time. */
    HttpResponseSpec send(HttpRequestSpec request);
}
