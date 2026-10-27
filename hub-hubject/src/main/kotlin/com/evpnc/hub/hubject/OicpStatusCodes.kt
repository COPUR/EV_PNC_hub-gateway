package com.evpnc.hub.hubject

/**
 * The official OICP 2.3 StatusCode list (`StatusCode.yaml` in the pinned contract; ADR source RH-HUBJECT-ERR-001).
 * Hubject's Integration Guide also mentions 401, 404 and 500, which are not in the spec list; they are not part of this
 * catalogue and [lookup] returns null for them, so callers treat them as unknown, log and fail safe (never crash).
 */
enum class OicpStatus(val code: String, val description: String, val area: Area) {
    SUCCESS("000", "Success", Area.GENERAL),
    HUBJECT_SYSTEM_ERROR("001", "Hubject system error", Area.INTERNAL),
    HUBJECT_DATABASE_ERROR("002", "Hubject database error", Area.INTERNAL),
    DATA_TRANSACTION_ERROR("009", "Data transaction error", Area.INTERNAL),
    UNAUTHORIZED_ACCESS("017", "Unauthorized Access", Area.INTERNAL),
    INCONSISTENT_EVSE_ID("018", "Inconsistent EvseID", Area.INTERNAL),
    INCONSISTENT_EVCO_ID("019", "Inconsistent EvcoID", Area.INTERNAL),
    SYSTEM_ERROR("021", "System error", Area.GENERAL),
    DATA_ERROR("022", "Data error", Area.GENERAL),
    QR_INVALID_CREDENTIALS("101", "QR Code Authentication failed - Invalid Credentials", Area.AUTHENTICATION),
    RFID_INVALID_UID("102", "RFID Authentication failed - invalid UID", Area.AUTHENTICATION),
    RFID_NOT_READABLE("103", "RFID Authentication failed - card not readable", Area.AUTHENTICATION),
    PLC_INVALID_EVCO_ID("105", "PLC Authentication failed - invalid EvcoID", Area.AUTHENTICATION),
    NO_POSITIVE_AUTHENTICATION("106", "No positive authentication response", Area.AUTHENTICATION),
    QR_APP_TIMEOUT("110", "QR Code App Authentication failed - time out error", Area.AUTHENTICATION),
    PLC_INVALID_UNDERLYING_EVCO_ID("120", "PLC (ISO/IEC 15118) Authentication failed - invalid underlying EvcoID", Area.AUTHENTICATION),
    PLC_INVALID_CERTIFICATE("121", "PLC (ISO/IEC 15118) Authentication failed - invalid certificate", Area.AUTHENTICATION),
    PLC_TIMEOUT("122", "PLC (ISO/IEC 15118) Authentication failed - time out error", Area.AUTHENTICATION),
    EVCO_ID_LOCKED("200", "EvcoID locked", Area.AUTHENTICATION),
    NO_VALID_CONTRACT("210", "No valid contract", Area.SESSION),
    PARTNER_NOT_FOUND("300", "Partner not found", Area.SESSION),
    PARTNER_DID_NOT_RESPOND("310", "Partner did not respond", Area.SESSION),
    SERVICE_NOT_AVAILABLE("320", "Service not available", Area.SESSION),
    SESSION_INVALID("400", "Session is invalid", Area.SESSION),
    EVSE_COMMUNICATION_FAILED("501", "Communication to EVSE failed", Area.EVSE),
    NO_EV_CONNECTED("510", "No EV connected to EVSE", Area.EVSE),
    EVSE_ALREADY_RESERVED("601", "EVSE already reserved", Area.EVSE),
    EVSE_IN_USE("602", "EVSE already in use / wrong token", Area.EVSE),
    UNKNOWN_EVSE_ID("603", "Unknown EVSE ID", Area.EVSE),
    EVSE_NOT_HUBJECT_COMPATIBLE("604", "EVSE ID is not Hubject compatible", Area.EVSE),
    EVSE_OUT_OF_SERVICE("700", "EVSE out of service", Area.EVSE),
    ;

    enum class Area { GENERAL, INTERNAL, AUTHENTICATION, SESSION, EVSE }

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun lookup(code: String): OicpStatus? = byCode[code]

        /** Bounds from StatusCode.yaml: Description up to 200 characters, AdditionalInfo up to 1000. */
        const val MAX_ADDITIONAL_INFO = 1000
    }
}
