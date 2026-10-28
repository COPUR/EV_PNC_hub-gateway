package com.evpnc.hub.core;

public class HubException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Hub hub;
    private final FailureClass failureClass;
    private final String code;

    public HubException(Hub hub, FailureClass failureClass, String code, String message) {
        this(hub, failureClass, code, message, null);
    }

    public HubException(Hub hub, FailureClass failureClass, String code, String message, Throwable cause) {
        super("[" + hub.key() + "] " + message, cause);
        this.hub = hub;
        this.failureClass = failureClass;
        this.code = code;
    }

    public Hub hub() {
        return hub;
    }

    public FailureClass failureClass() {
        return failureClass;
    }

    public String code() {
        return code;
    }
}
