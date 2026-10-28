package com.evpnc.hub.core;

public sealed interface RemoteResult {

    /** @param hubSessionId the hub's session id, null when the hub did not assign one */
    record Accepted(String hubSessionId) implements RemoteResult {}

    record Rejected(String code, RemoteRejection reason, String detail) implements RemoteResult {
        public Rejected(String code, RemoteRejection reason) {
            this(code, reason, null);
        }
    }

    /** The hub did not answer within the CPO window; resolve through notifications, status or the CDR. */
    record UnknownState(String detail) implements RemoteResult {}
}
