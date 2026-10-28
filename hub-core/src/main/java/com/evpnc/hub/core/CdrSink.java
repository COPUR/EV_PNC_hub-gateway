package com.evpnc.hub.core;

/** Where received CDRs go (payments, invoicing). Implemented by the platform, never by an adapter. */
@FunctionalInterface
public interface CdrSink {
    void accept(HubCdr cdr);
}
