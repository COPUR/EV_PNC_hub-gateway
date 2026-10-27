package com.evpnc.hub.gireve

import com.evpnc.hub.core.AuthChannel
import com.evpnc.hub.core.AuthorizationPolicy
import com.evpnc.hub.core.AuthorizeDecision
import com.evpnc.hub.core.AuthorizeRequest
import com.evpnc.hub.core.DecisionOutcome
import com.evpnc.hub.core.DecisionReason
import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.HttpRequestSpec
import com.evpnc.hub.core.TokenRef
import com.evpnc.hub.core.TokenType
import com.evpnc.hub.core.ocpi221.Ocpi221Compat
import com.evpnc.hub.core.ocpi221.OcpiHeaders
import com.evpnc.hub.core.ocpi221.OcpiObject
import com.evpnc.hub.core.resilience.GuardedDecision
import com.evpnc.hub.core.resilience.GuardedPolicy
import com.evpnc.hub.core.resilience.TimeBudget
import java.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Answers `POST tokens/{uid}/authorize`, which GIREVE (the "IOP") sends us when a roaming driver identifies at a CPO's EVSE
 * (RH-GIREVE-TOK-001, ERR-001, ERR-002). The answer follows OCPI: an unknown token is status 2004 with no data, a known token
 * that is refused is AuthorizationInfo with `allowed` BLOCKED, NO_CREDIT or NOT_ALLOWED, and a request without location
 * references is 2002 because GIREVE requires them.
 */
class GireveAuthorizeHandler(
    private val config: GireveConfig,
    private val profile: GireveProfile,
    private val compat: Ocpi221Compat,
    private val policy: GuardedPolicy,
    private val clock: Clock,
) {
    fun handle(tokenUid: String, type: String?, body: String?): OcpiResponse {
        val now = clock.instant()
        fun reply(code: Int, message: String, data: kotlinx.serialization.json.JsonElement? = null) = OcpiResponse(code, message, data, now)

        val locationReferences = parseLocationReferences(body)
        if (profile.locationReferenceRequired && locationReferences == null) {
            return reply(OcpiStatusCodes.NOT_ENOUGH_INFORMATION, "location_references are required")
        }

        val token = identify(tokenUid, type) ?: return reply(OcpiStatusCodes.UNKNOWN_TOKEN, "Unknown Token")
        val request = AuthorizeRequest(
            hub = Hub.GIREVE,
            requestId = "gireve-${now.toEpochMilli()}-${token.uid}",
            channel = AuthChannel.TOKEN_AUTHORIZE,
            token = token,
            receivedAt = now,
            evseId = locationReferences?.get("evse_uids")?.let { (it as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull },
            operatorId = locationReferences?.get("location_id")?.jsonPrimitive?.contentOrNull,
        )

        return when (val guarded = policy.decide(request, TimeBudget.starting(clock, config.authorizeBudget))) {
            is GuardedDecision.Decided -> answer(guarded.decision, request, locationReferences, now)
            GuardedDecision.TimedOut, is GuardedDecision.PolicyFailed ->
                // OCPI has no "try later" for this call; refusing is the safe answer when we cannot decide.
                reply(OcpiStatusCodes.SERVER_ERROR, "authorization unavailable")
        }
    }

    private fun answer(decision: AuthorizeDecision, request: AuthorizeRequest, location: JsonObject?, now: java.time.Instant): OcpiResponse =
        when (decision) {
            is AuthorizeDecision.Allowed ->
                OcpiResponse(OcpiStatusCodes.SUCCESS, "Success", info("ALLOWED", request.token, location, decision.authorizationReference, now), now)
            is AuthorizeDecision.Denied ->
                if (DecisionReason.TOKEN_UNKNOWN in decision.reasons) {
                    OcpiResponse(OcpiStatusCodes.UNKNOWN_TOKEN, "Unknown Token", null, now)
                } else {
                    OcpiResponse(OcpiStatusCodes.SUCCESS, "Success", info(allowedType(decision.outcome), request.token, null, null, now), now)
                }
        }

    private fun allowedType(outcome: DecisionOutcome) = when (outcome) {
        DecisionOutcome.ALLOWED -> "ALLOWED"
        DecisionOutcome.BLOCKED -> "BLOCKED"
        DecisionOutcome.NO_CREDIT -> "NO_CREDIT"
        DecisionOutcome.NOT_ALLOWED -> "NOT_ALLOWED"
    }

    private fun info(allowed: String, token: TokenRef, location: JsonObject?, reference: String?, now: java.time.Instant): JsonObject = buildJsonObject {
        put("allowed", allowed)
        put("token", compat.outbound(OcpiObject.TOKEN, canonicalToken(config, token, now.toString())))
        // Only the EVSEs the driver may use are returned; with one decision for the location all referenced EVSEs are allowed.
        if (allowed == "ALLOWED" && location != null) put("location", location)
        reference?.let { put("authorization_reference", it.take(36)) }
    }

    /** Token uid + type as the IOP sent them, converted to the canonical type (OTHER holding an eMAID becomes EMAID). */
    private fun identify(uid: String, type: String?): TokenRef? {
        val wireType = (type ?: "RFID").uppercase()
        val normalised = if (wireType == "RFID") RfidUid.normalise(uid, profile.uidByteLengths) ?: return null else uid.trim().ifEmpty { return null }
        val converted = compat.inbound(
            OcpiObject.TOKEN,
            buildJsonObject {
                put("uid", normalised)
                put("type", wireType)
            },
        ).json
        val canonicalType = runCatching { TokenType.valueOf(converted["type"]!!.jsonPrimitive.content) }.getOrNull() ?: return null
        return TokenRef(normalised, canonicalType, converted["contract_id"]?.jsonPrimitive?.contentOrNull)
    }

    private fun parseLocationReferences(body: String?): JsonObject? {
        val parsed = runCatching { Json.parseToJsonElement(body ?: "").jsonObject }.getOrNull() ?: return null
        return parsed.takeIf { it["location_id"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true }
    }

    companion object {
        /** A canonical OCPI 2.3.0 Token for [token]. `valid` is true: a token the platform authorises is by definition valid. */
        fun canonicalToken(config: GireveConfig, token: TokenRef, lastUpdated: String): JsonObject = buildJsonObject {
            put("country_code", config.ourParty.countryCode)
            put("party_id", config.ourParty.partyId)
            put("uid", token.uid)
            put("type", token.type.name)
            put("contract_id", token.contractId ?: token.uid)
            put("issuer", config.issuer)
            put("valid", true)
            put("whitelist", "NEVER")
            put("last_updated", JsonPrimitive(lastUpdated))
        }
    }
}

/**
 * Builds token pushes to the IOP. GIREVE implements PUT only (no PATCH, no pull), does not take Plug&Charge tokens (they are
 * authorised in real time by eMAID), and wants RFID UIDs normalised (RH-GIREVE-TOK-002..004).
 */
class GireveTokenPublisher(
    private val config: GireveConfig,
    private val profile: GireveProfile,
    private val compat: Ocpi221Compat,
) {
    /** @return the request, or null when this token must not be pushed to GIREVE. */
    fun putRequest(token: JsonObject, requestId: String, correlationId: String): HttpRequestSpec? {
        if (token["type"]?.jsonPrimitive?.contentOrNull == "EMAID") return null
        val uid = token["uid"]?.jsonPrimitive?.contentOrNull ?: return null
        val normalisedUid = if (token["type"]?.jsonPrimitive?.contentOrNull == "RFID") {
            RfidUid.normalise(uid, profile.uidByteLengths) ?: return null
        } else {
            uid
        }
        val body = compat.outbound(OcpiObject.TOKEN, JsonObject(token + ("uid" to JsonPrimitive(normalisedUid))))
        val hub = profile.hubParty(config.environment)
        return HttpRequestSpec(
            method = "PUT",
            path = "${config.tokensPath}/${config.ourParty.countryCode}/${config.ourParty.partyId}/$normalisedUid",
            headers = OcpiHeaders.request("tokens", config.ourParty, hub, config.tokenForHub.reveal(), requestId, correlationId),
            body = Json.encodeToString(JsonObject.serializer(), body),
        )
    }
}
