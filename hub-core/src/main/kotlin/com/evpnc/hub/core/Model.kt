package com.evpnc.hub.core

import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** OCPI 2.3.0 token types, as the canonical model has them. */
enum class TokenType { RFID, APP_USER, AD_HOC_USER, EMAID, OTHER }

/** How the driver identified at the charge point. Hubject calls this the identification variant. */
enum class AuthChannel { RFID, QR_CODE, PLUG_AND_CHARGE, REMOTE, TOKEN_AUTHORIZE }

/** A value that must never reach a log line (QR PIN, tokens, client secrets). */
class Secret(private val value: String) {
    fun reveal(): String = value

    override fun toString(): String = "***"

    override fun equals(other: Any?): Boolean = other is Secret && other.value == value

    override fun hashCode(): Int = value.hashCode()
}

data class TokenRef(
    val uid: String,
    val type: TokenType,
    /** EvcoID / eMAID when the token carries one. */
    val contractId: String? = null,
)

data class AuthorizeRequest(
    val hub: Hub,
    /** Our id for this request; the hub's session id when the hub assigns one. */
    val requestId: String,
    val channel: AuthChannel,
    val token: TokenRef,
    val receivedAt: Instant,
    val evseId: String? = null,
    val operatorId: String? = null,
    val hubSessionId: String? = null,
    val pin: Secret? = null,
)

/** Same values as the `DecisionOutcome` and `DecisionReason` enums in EV_PNC_ocpi-model (events schema). */
enum class DecisionOutcome { ALLOWED, BLOCKED, NO_CREDIT, NOT_ALLOWED }

enum class DecisionReason {
    TOKEN_UNKNOWN,
    TOKEN_INVALID,
    NO_GRANT,
    GRANT_REVOKED,
    OPERATOR_BLOCKED,
    OPERATOR_NOT_ALLOWED,
    PRICE_OVER_THRESHOLD,
    PAYMENT_UNAVAILABLE,
    LOCATION_NOT_REFERENCED,
    OTHER,
}

sealed interface AuthorizeDecision {
    data class Allowed(
        val stopTokens: List<TokenRef> = emptyList(),
        /** Reference the hub should repeat in the session and CDR (OCPI `authorization_reference`). */
        val authorizationReference: String? = null,
    ) : AuthorizeDecision

    data class Denied(
        val outcome: DecisionOutcome,
        val reasons: List<DecisionReason>,
        val detail: String? = null,
    ) : AuthorizeDecision {
        init {
            require(outcome != DecisionOutcome.ALLOWED) { "Denied cannot carry ALLOWED" }
        }
    }
}

enum class PricedBy { HUB, CPO, NOBODY }

/**
 * A charge detail record as received from a hub, normalised just enough for the platform.
 * Hubject CDRs carry no price (the payments service prices them); GIREVE CDRs are full OCPI
 * and arrive in [ocpi] already converted to the canonical 2.3.0 shape.
 */
data class HubCdr(
    val hub: Hub,
    /** Hub session id. With [hub] it is the idempotency key (RH-HUBJECT-ERR-006). */
    val hubSessionId: String,
    val evseId: String?,
    val token: TokenRef?,
    val sessionStart: Instant?,
    val sessionEnd: Instant?,
    val energyKwh: Double?,
    val pricedBy: PricedBy,
    val ocpi: JsonObject? = null,
    /** Hub data with no canonical home, under `extensions.<hub>` like the canonical model does. */
    val extensions: JsonObject = JsonObject(emptyMap()),
    val chargingStart: Instant? = null,
    val chargingEnd: Instant? = null,
    val meterStartKwh: Double? = null,
    val meterEndKwh: Double? = null,
)

sealed interface RemoteResult {
    data class Accepted(val hubSessionId: String?) : RemoteResult

    data class Rejected(val code: String?, val reason: RemoteRejection, val detail: String? = null) : RemoteResult

    /** The hub did not answer within the CPO window; resolve through notifications, status or the CDR. */
    data class UnknownState(val detail: String) : RemoteResult
}

enum class RemoteRejection {
    NO_VALID_CONTRACT,
    PARTNER_UNKNOWN,
    PARTNER_UNREACHABLE,
    EVSE_UNREACHABLE,
    NO_VEHICLE_CONNECTED,
    EVSE_RESERVED,
    EVSE_IN_USE,
    EVSE_UNKNOWN,
    EVSE_NOT_ROAMABLE,
    EVSE_OUT_OF_SERVICE,
    CONFIGURATION,
    OTHER,
}

data class RemoteStart(
    val hub: Hub,
    val requestId: String,
    val token: TokenRef,
    val evseId: String,
    /** CPO's location id; GIREVE needs it, Hubject does not. */
    val locationId: String? = null,
    val connectorId: String? = null,
)

data class RemoteStop(
    val hub: Hub,
    val hubSessionId: String,
    val evseId: String,
)
