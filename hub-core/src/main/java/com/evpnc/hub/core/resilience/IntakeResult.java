package com.evpnc.hub.core.resilience;

public enum IntakeResult {
    /** Handed to the sink. */
    STORED,

    /** Seen before (the hub resent, or reconciliation found it again). Nothing was done. */
    DUPLICATE,

    /** The sink failed; the record is parked and will be replayed. The hub still gets a positive answer. */
    PARKED
}
