package com.evpnc.hub.hubject;

import com.evpnc.hub.core.resilience.CdrIntake;
import com.evpnc.hub.core.resilience.IntakeResult;

/**
 * Receives Hubject's eRoamingChargeDetailRecord. Hubject never resends a CDR that failed, so the answer is positive as
 * soon as the record is safe (stored, or parked for replay), and a resend is acknowledged without a second delivery
 * (RH-HUBJECT-ERR-006). Only a record without a SessionID is refused: it cannot be keyed or reconciled.
 */
public final class HubjectCdrHandler {
    private final CdrIntake intake;

    public HubjectCdrHandler(CdrIntake intake) {
        this.intake = intake;
    }

    public OicpWire.Acknowledgement handle(OicpWire.ChargeDetailRecord record) {
        if (record.sessionId().isBlank()) {
            return new OicpWire.Acknowledgement(false, HubjectAuthorizeHandler.statusCode(OicpStatus.DATA_ERROR, "SessionID missing"));
        }
        IntakeResult result = intake.receive(HubjectMapping.cdr(record));
        String info = switch (result) {
            case STORED -> null;
            case DUPLICATE -> "duplicate, already received";
            case PARKED -> "accepted, processing deferred";
        };
        return new OicpWire.Acknowledgement(
                true,
                HubjectAuthorizeHandler.statusCode(OicpStatus.SUCCESS, info),
                record.sessionId(),
                record.cpoPartnerSessionId(),
                record.empPartnerSessionId());
    }
}
