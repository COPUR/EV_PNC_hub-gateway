package com.evpnc.hub.gireve;

/** OCPI status codes the adapter uses (meanings from the OCPI status_codes chapter). */
public final class OcpiStatusCodes {
    public static final int SUCCESS = 1000;
    public static final int CLIENT_ERROR = 2000;
    public static final int INVALID_PARAMETERS = 2001;
    public static final int NOT_ENOUGH_INFORMATION = 2002;
    public static final int UNKNOWN_TOKEN = 2004;
    public static final int SERVER_ERROR = 3000;
    public static final int UNKNOWN_RECEIVER = 4001;
    public static final int FORWARDED_REQUEST_TIMEOUT = 4002;
    public static final int RECEIVER_NOT_CONNECTED = 4003;

    private OcpiStatusCodes() {}
}
