package com.evpnc.hub.core;

public class TransportTimeout extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TransportTimeout(String message) {
        super(message);
    }
}
