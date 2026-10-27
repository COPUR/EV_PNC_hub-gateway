package com.evpnc.hub.gireve

import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.HubCdr
import com.evpnc.hub.core.TokenRef
import com.evpnc.hub.core.TokenType
import com.evpnc.hub.core.ocpi221.Ocpi221Compat
import com.evpnc.hub.core.ocpi221.OcpiObject
import com.evpnc.hub.core.resilience.CdrIntake
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Receives `POST cdrs` from the IOP. The IOP stores and forwards, so a CDR can arrive again; it is kept once, keyed by the
 * hub's session id (RH-GIREVE-SESSION-001, ERR-008). The full CDR is converted to the canonical 2.3.0 shape and travels in
 * [HubCdr.ocpi]; the remark prefix says who priced it (ERR-009: `[?]` means we price it ourselves).
 */
class GireveCdrHandler(
    private val compat: Ocpi221Compat,
    private val intake: CdrIntake,
    private val clock: Clock,
) {
    fun handle(body: String): OcpiResponse {
        val now = clock.instant()
        val cdr = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return OcpiResponse(OcpiStatusCodes.INVALID_PARAMETERS, "body is not a CDR", null, now)
        val id = cdr["id"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank()) return OcpiResponse(OcpiStatusCodes.INVALID_PARAMETERS, "id missing", null, now)

        val canonical = compat.inbound(OcpiObject.CDR, cdr).json
        intake.receive(toHubCdr(canonical, id))
        // Positive even when the downstream sink is down: the CDR is parked and replayed (store-and-forward is the IOP's job too).
        return OcpiResponse(OcpiStatusCodes.SUCCESS, "Success", null, now)
    }

    private fun toHubCdr(cdr: JsonObject, id: String): HubCdr {
        val location = cdr["cdr_location"] as? JsonObject
        val token = cdr["cdr_token"] as? JsonObject
        return HubCdr(
            hub = Hub.GIREVE,
            hubSessionId = cdr["session_id"]?.jsonPrimitive?.contentOrNull
                ?: "cdr:${cdr["country_code"]?.jsonPrimitive?.contentOrNull}-${cdr["party_id"]?.jsonPrimitive?.contentOrNull}-$id",
            evseId = location?.get("evse_id")?.jsonPrimitive?.contentOrNull ?: location?.get("evse_uid")?.jsonPrimitive?.contentOrNull,
            token = token?.let {
                TokenRef(
                    uid = it["uid"]?.jsonPrimitive?.contentOrNull ?: return@let null,
                    type = runCatching { TokenType.valueOf(it["type"]!!.jsonPrimitive.content) }.getOrDefault(TokenType.OTHER),
                    contractId = it["contract_id"]?.jsonPrimitive?.contentOrNull,
                )
            },
            sessionStart = instant(cdr["start_date_time"]?.jsonPrimitive?.contentOrNull),
            sessionEnd = instant(cdr["end_date_time"]?.jsonPrimitive?.contentOrNull),
            energyKwh = cdr["total_energy"]?.jsonPrimitive?.doubleOrNull,
            pricedBy = GireveCdrPricing.classify(cdr["remark"]?.jsonPrimitive?.contentOrNull),
            ocpi = cdr,
        )
    }

    private fun instant(text: String?): Instant? = try {
        text?.let { Instant.parse(if (it.endsWith("Z")) it else it + "Z") }
    } catch (_: DateTimeParseException) {
        null
    }
}
