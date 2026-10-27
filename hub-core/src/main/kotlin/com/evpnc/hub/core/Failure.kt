package com.evpnc.hub.core

/** How the caller should react to a failure. Decided once here so adapters do not each invent a policy. */
enum class FailureClass {
    /** Try again later with backoff (hub down, 429, 5xx). */
    RETRYABLE,

    /** The request is wrong or refused; retrying cannot help. */
    NON_RETRYABLE,

    /** Our certificate, ProviderID, IP allow-list or profile is wrong. Alert a human, do not retry. */
    CONFIGURATION,

    /** We cannot tell whether the hub acted (remote start timed out). Resolve from notifications, status or CDR. */
    UNKNOWN_STATE,
}

class HubException(
    val hub: Hub,
    val failureClass: FailureClass,
    val code: String?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("[${hub.key}] $message", cause)
