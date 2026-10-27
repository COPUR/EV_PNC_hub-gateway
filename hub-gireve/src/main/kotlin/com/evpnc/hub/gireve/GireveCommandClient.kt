package com.evpnc.hub.gireve

import com.evpnc.hub.core.FailureClass
import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.HttpRequestSpec
import com.evpnc.hub.core.HubException
import com.evpnc.hub.core.HubTransport
import com.evpnc.hub.core.RemoteRejection
import com.evpnc.hub.core.RemoteResult
import com.evpnc.hub.core.RemoteSessionPort
import com.evpnc.hub.core.RemoteStart
import com.evpnc.hub.core.RemoteStop
import com.evpnc.hub.core.TransportTimeout
import com.evpnc.hub.core.ocpi221.Ocpi221Compat
import com.evpnc.hub.core.ocpi221.OcpiHeaders
import com.evpnc.hub.core.ocpi221.OcpiObject
import java.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * START_SESSION and STOP_SESSION through the IOP. `ACCEPTED` only means the IOP took the command; the real result arrives
 * later at our callback as a CommandResult, and if none arrives within `CommandResponse.timeout` the command may never have
 * reached the charge point (RH-GIREVE-ERR-003). Reserve, cancel and unlock are refused before any call (CMD-002).
 */
class GireveCommandClient(
    private val config: GireveConfig,
    private val profile: GireveProfile,
    private val compat: Ocpi221Compat,
    private val transport: HubTransport,
    private val clock: Clock,
) : RemoteSessionPort {

    override fun start(command: RemoteStart): RemoteResult {
        compat.checkCommand("START_SESSION")
        val locationId = command.locationId
            ?: throw HubException(Hub.GIREVE, FailureClass.NON_RETRYABLE, "LOCATION_REQUIRED", "START_SESSION needs the CPO's location_id")
        val body = compat.outbound(
            OcpiObject.START_SESSION,
            buildJsonObject {
                put("response_url", "${config.callbackBase}/START_SESSION/${command.requestId}")
                put("token", GireveAuthorizeHandler.canonicalToken(config, command.token, clock.instant().toString()))
                put("location_id", locationId)
                put("evse_uid", command.evseId)
                command.connectorId?.let { put("connector_id", it) }
            },
        )
        return send("START_SESSION", command.requestId, body)
    }

    override fun stop(command: RemoteStop): RemoteResult {
        compat.checkCommand("STOP_SESSION")
        val body = buildJsonObject {
            put("response_url", "${config.callbackBase}/STOP_SESSION/${command.hubSessionId}")
            put("session_id", command.hubSessionId)
        }
        return send("STOP_SESSION", command.hubSessionId, body)
    }

    private fun send(type: String, requestId: String, body: JsonObject): RemoteResult {
        val hub = profile.hubParty(config.environment)
        val request = HttpRequestSpec(
            method = "POST",
            path = "${config.commandsPath}/$type",
            headers = OcpiHeaders.request("commands", config.ourParty, hub, config.tokenForHub.reveal(), requestId, requestId),
            body = Json.encodeToString(JsonObject.serializer(), body),
        )
        val response = try {
            transport.send(request)
        } catch (e: TransportTimeout) {
            return RemoteResult.UnknownState("no answer from the IOP: ${e.message}")
        }
        if (response.status in profile.waitStatuses || response.status >= 500) {
            throw HubException(Hub.GIREVE, FailureClass.RETRYABLE, response.status.toString(), "IOP asked us to wait or failed")
        }
        if (response.status !in 200..299) {
            throw HubException(Hub.GIREVE, FailureClass.NON_RETRYABLE, response.status.toString(), "IOP refused the command")
        }
        val envelope = OcpiResponse.parse(response.body)
            ?: throw HubException(Hub.GIREVE, FailureClass.NON_RETRYABLE, null, "answer is not an OCPI response")
        return when (envelope.statusCode) {
            OcpiStatusCodes.SUCCESS -> commandResult(envelope)
            OcpiStatusCodes.UNKNOWN_RECEIVER -> RemoteResult.Rejected("4001", RemoteRejection.PARTNER_UNKNOWN, envelope.message)
            OcpiStatusCodes.RECEIVER_NOT_CONNECTED -> RemoteResult.Rejected("4003", RemoteRejection.PARTNER_UNREACHABLE, envelope.message)
            OcpiStatusCodes.FORWARDED_REQUEST_TIMEOUT -> RemoteResult.UnknownState("IOP timed out forwarding the command (4002)")
            in 3000..3999 -> throw HubException(Hub.GIREVE, FailureClass.RETRYABLE, envelope.statusCode.toString(), "IOP server error")
            else -> throw HubException(Hub.GIREVE, FailureClass.NON_RETRYABLE, envelope.statusCode.toString(), envelope.message ?: "client error")
        }
    }

    private fun commandResult(envelope: OcpiResponse): RemoteResult {
        val result = envelope.data?.jsonObject?.get("result")?.jsonPrimitive?.contentOrNull
        return when (result) {
            "ACCEPTED" -> RemoteResult.Accepted(null)
            "REJECTED", "NOT_SUPPORTED", "UNKNOWN_SESSION" -> RemoteResult.Rejected(result, RemoteRejection.OTHER)
            else -> throw HubException(Hub.GIREVE, FailureClass.NON_RETRYABLE, null, "unknown command result '$result'")
        }
    }
}
