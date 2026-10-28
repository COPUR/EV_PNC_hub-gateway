package com.evpnc.hub.core;

/** How the caller should react to a failure. Decided once here so adapters do not each invent a policy. */
public enum FailureClass {
    /** Try again later with backoff (hub down, 429, 5xx). */
    RETRYABLE,

    /** The request is wrong or refused; retrying cannot help. */
    NON_RETRYABLE,

    /** Our certificate, ProviderID, IP allow-list or profile is wrong. Alert a human, do not retry. */
    CONFIGURATION,

    /** We cannot tell whether the hub acted (remote start timed out). Resolve from notifications, status or CDR. */
    UNKNOWN_STATE
}
