package com.evpnc.hub.hubject

import com.evpnc.hub.core.AuthChannel
import com.evpnc.hub.core.HubCdr
import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.PricedBy
import com.evpnc.hub.core.Secret
import com.evpnc.hub.core.TokenRef
import com.evpnc.hub.core.TokenType
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** OICP identification -> platform token, and back. Patterns come from the pinned contract. */
object HubjectMapping {
    private val UID = Regex("^([0-9A-F]{8}|[0-9A-F]{14}|[0-9A-F]{20})$")
    val EVCO_ID = Regex(
        "^(([A-Za-z]{2}-?[A-Za-z0-9]{3}-?C[A-Za-z0-9]{8}-?[\\d|A-Za-z])|([A-Za-z]{2}[*|-]?[A-Za-z0-9]{3}[*|-]?[A-Za-z0-9]{6}[*|-]?[\\d|X]))$",
    )
    val SESSION_ID = Regex("^[A-Za-z0-9]{8}(-[A-Za-z0-9]{4}){3}-[A-Za-z0-9]{12}$")

    class Identified(val channel: AuthChannel, val token: TokenRef, val pin: Secret?)

    sealed interface IdentificationResult {
        data class Ok(val identified: Identified) : IdentificationResult

        /** The identification is unusable; [channel] says which refusal code fits. */
        data class Invalid(val channel: AuthChannel?, val problem: String) : IdentificationResult
    }

    /** Exactly one variant must be present. A UID or EvcoID that breaks the contract's pattern is refused, not guessed at. */
    fun identify(identification: OicpIdentification): IdentificationResult {
        val present = listOfNotNull(
            identification.rfidMifareFamily, identification.rfid, identification.qrCode,
            identification.plugAndCharge, identification.remote,
        )
        if (present.size != 1) return IdentificationResult.Invalid(null, "expected exactly one identification variant, got ${present.size}")

        identification.rfidMifareFamily?.let { return rfid(it.uid, null) }
        identification.rfid?.let { return rfid(it.uid, it.evcoId) }
        identification.qrCode?.let { qr ->
            if (!EVCO_ID.matches(qr.evcoId)) return IdentificationResult.Invalid(AuthChannel.QR_CODE, "EvcoID breaks the contract pattern")
            val pin = (qr.pin ?: qr.hashedPin?.value)?.let(::Secret)
            return IdentificationResult.Ok(Identified(AuthChannel.QR_CODE, TokenRef(qr.evcoId, TokenType.APP_USER, qr.evcoId), pin))
        }
        identification.plugAndCharge?.let { return evco(it.evcoId, AuthChannel.PLUG_AND_CHARGE, TokenType.EMAID) }
        identification.remote?.let { return evco(it.evcoId, AuthChannel.REMOTE, TokenType.APP_USER) }
        error("unreachable")
    }

    private fun rfid(uid: String, evcoId: String?): IdentificationResult =
        if (!UID.matches(uid)) {
            IdentificationResult.Invalid(AuthChannel.RFID, "UID breaks the contract pattern")
        } else {
            IdentificationResult.Ok(Identified(AuthChannel.RFID, TokenRef(uid, TokenType.RFID, evcoId), null))
        }

    private fun evco(evcoId: String, channel: AuthChannel, type: TokenType): IdentificationResult =
        if (!EVCO_ID.matches(evcoId)) {
            IdentificationResult.Invalid(channel, "EvcoID breaks the contract pattern")
        } else {
            IdentificationResult.Ok(Identified(channel, TokenRef(evcoId, type, evcoId), null))
        }

    /** Identification we send to Hubject (remote start) or list as allowed to stop. */
    fun identification(token: TokenRef): OicpIdentification = when {
        token.type == TokenType.RFID && UID.matches(token.uid) -> OicpIdentification(rfidMifareFamily = OicpRfidMifareFamily(token.uid))
        token.type == TokenType.EMAID -> OicpIdentification(plugAndCharge = OicpEvco(token.contractId ?: token.uid))
        else -> OicpIdentification(remote = OicpEvco(token.contractId ?: token.uid))
    }

    /** OICP timestamps carry an offset (TR is +03:00); they are stored as instants. */
    fun instant(text: String): Instant? = try {
        OffsetDateTime.parse(text).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

    /**
     * OICP CDR -> platform CDR. Never throws on content: a record Hubject will not resend must not be lost to a
     * parsing problem, so unreadable fields become null and the original values stay under `extensions.oicp`.
     */
    fun cdr(record: OicpChargeDetailRecord): HubCdr {
        val identified = (identify(record.identification) as? IdentificationResult.Ok)?.identified
        return HubCdr(
            hub = Hub.HUBJECT,
            hubSessionId = record.sessionId,
            evseId = record.evseId,
            token = identified?.token,
            sessionStart = instant(record.sessionStart),
            sessionEnd = instant(record.sessionEnd),
            energyKwh = record.consumedEnergy,
            pricedBy = PricedBy.NOBODY,
            chargingStart = instant(record.chargingStart),
            chargingEnd = instant(record.chargingEnd),
            meterStartKwh = record.meterValueStart,
            meterEndKwh = record.meterValueEnd,
            extensions = buildJsonObject {
                put("oicp", buildJsonObject {
                    put("session_start", record.sessionStart)
                    put("session_end", record.sessionEnd)
                    put("charging_start", record.chargingStart)
                    put("charging_end", record.chargingEnd)
                    record.partnerProductId?.let { put("partner_product_id", it) }
                    record.hubOperatorId?.let { put("hub_operator_id", it) }
                    record.hubProviderId?.let { put("hub_provider_id", it) }
                    record.empPartnerSessionId?.let { put("emp_partner_session_id", it) }
                    record.cpoPartnerSessionId?.let { put("cpo_partner_session_id", it) }
                    record.signedMeteringValues?.let { values ->
                        put("signed_metering", JsonArray(values.map { v ->
                            buildJsonObject {
                                put("status", v.status)
                                put("value", v.value)
                            }
                        }))
                    }
                    record.calibration?.let { c ->
                        put("calibration_law", buildJsonObject {
                            c.certificateId?.let { put("certificate_id", it) }
                            c.publicKey?.let { put("public_key", it) }
                            c.meteringSignatureUrl?.let { put("metering_signature_url", it) }
                            c.encodingFormat?.let { put("encoding_format", it) }
                            c.verificationInstruction?.let { put("verification_instruction", it) }
                        })
                    }
                })
            },
        )
    }
}
