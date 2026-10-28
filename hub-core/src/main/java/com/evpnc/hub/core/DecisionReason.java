package com.evpnc.hub.core;

/** Same values as the `DecisionReason` enum in EV_PNC_ocpi-model (events schema). */
public enum DecisionReason {
    TOKEN_UNKNOWN,
    TOKEN_INVALID,
    NO_GRANT,
    GRANT_REVOKED,
    OPERATOR_BLOCKED,
    OPERATOR_NOT_ALLOWED,
    PRICE_OVER_THRESHOLD,
    PAYMENT_UNAVAILABLE,
    LOCATION_NOT_REFERENCED,
    OTHER
}
