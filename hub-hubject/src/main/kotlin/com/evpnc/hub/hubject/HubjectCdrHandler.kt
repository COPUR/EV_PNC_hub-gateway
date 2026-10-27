package com.evpnc.hub.hubject

import com.evpnc.hub.core.resilience.CdrIntake
import com.evpnc.hub.core.resilience.IntakeResult

/**
 * Receives Hubject's eRoamingChargeDetailRecord. Hubject never resends a CDR that failed, so the answer is positive as soon
 * as the record is safe (stored, or parked for replay), and a resend is acknowledged without a second delivery
 * (RH-HUBJECT-ERR-006). Only a record without a SessionID is refused: it cannot be keyed or reconciled.
 */
class HubjectCdrHandler(private val intake: CdrIntake) {
    fun handle(record: OicpChargeDetailRecord): OicpAcknowledgement {
        if (record.sessionId.isBlank()) {
            return OicpAcknowledgement(false, HubjectAuthorizeHandler.statusCode(OicpStatus.DATA_ERROR, "SessionID missing"))
        }
        val result = intake.receive(HubjectMapping.cdr(record))
        val info = when (result) {
            IntakeResult.STORED -> null
            IntakeResult.DUPLICATE -> "duplicate, already received"
            IntakeResult.PARKED -> "accepted, processing deferred"
        }
        return OicpAcknowledgement(
            result = true,
            statusCode = HubjectAuthorizeHandler.statusCode(OicpStatus.SUCCESS, info),
            sessionId = record.sessionId,
            cpoPartnerSessionId = record.cpoPartnerSessionId,
            empPartnerSessionId = record.empPartnerSessionId,
        )
    }
}
