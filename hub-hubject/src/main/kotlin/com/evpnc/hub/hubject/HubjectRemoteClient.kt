package com.evpnc.hub.hubject

import com.evpnc.hub.core.FailureClass
import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.HttpRequestSpec
import com.evpnc.hub.core.HttpResponseSpec
import com.evpnc.hub.core.HubException
import com.evpnc.hub.core.HubTransport
import com.evpnc.hub.core.RemoteRejection
import com.evpnc.hub.core.RemoteResult
import com.evpnc.hub.core.RemoteSessionPort
import com.evpnc.hub.core.RemoteStart
import com.evpnc.hub.core.RemoteStop
import com.evpnc.hub.core.TransportTimeout
import com.evpnc.hub.core.resilience.Throttler
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

/**
 * Remote start and stop through the Hubject HBS. Requests are built as data and sent by a [HubTransport], so the mTLS
 * client is replaceable and the logic is testable without a network (and without touching a live system).
 *
 * Hubject decides success from `Result` and the StatusCode, not from the HTTP status (RH-HUBJECT-ERR-002). A timeout is
 * not a failure: the CPO may have started anyway, so the state is unknown until a notification, status or CDR says
 * otherwise (RH-HUBJECT-ERR-005). The caller's transport must use [HubjectProfile.remoteClientTimeout] (65 s).
 */
class HubjectRemoteClient(
    private val providerId: String,
    private val transport: HubTransport,
    private val throttler: Throttler,
) : RemoteSessionPort {

    override fun start(command: RemoteStart): RemoteResult {
        val body = AuthorizeRemoteStartRequest(
            providerId = providerId,
            evseId = command.evseId,
            identification = HubjectMapping.identification(command.token),
            sessionId = null,
            empPartnerSessionId = command.requestId,
        )
        return send(
            "AuthorizeRemoteStart",
            "/charging/v21/providers/$providerId/authorize-remote/start",
            OicpJson.json.encodeToString(body),
        )
    }

    override fun stop(command: RemoteStop): RemoteResult {
        val body = AuthorizeRemoteStopRequest(sessionId = command.hubSessionId, providerId = providerId, evseId = command.evseId)
        // The pinned contract names the path parameter externalID; the leading guide fills it with the ProviderID.
        return send(
            "AuthorizeRemoteStop",
            "/charging/v21/providers/$providerId/authorize-remote/stop",
            OicpJson.json.encodeToString(body),
        )
    }

    private fun send(operation: String, path: String, body: String): RemoteResult {
        if (!throttler.tryAcquire(operation)) {
            throw HubException(Hub.HUBJECT, FailureClass.RETRYABLE, "THROTTLED", "$operation over the Acceptable Use Policy limit; retry in ${throttler.waitTime(operation)}")
        }
        val response = try {
            transport.send(HttpRequestSpec("POST", path, mapOf("Content-Type" to "application/json"), body = body))
        } catch (e: TransportTimeout) {
            return RemoteResult.UnknownState("no answer from the HBS: ${e.message}")
        }
        val ack = parse(response)
        if (ack.result) return RemoteResult.Accepted(ack.sessionId)
        return rejection(ack.statusCode)
    }

    private fun parse(response: HttpResponseSpec): OicpAcknowledgement {
        when {
            response.status == 429 -> throw HubException(Hub.HUBJECT, FailureClass.RETRYABLE, "429", "rate limited by the HBS")
            response.status >= 500 -> throw HubException(Hub.HUBJECT, FailureClass.RETRYABLE, response.status.toString(), "HBS error")
            response.status != 200 -> throw HubException(Hub.HUBJECT, FailureClass.NON_RETRYABLE, response.status.toString(), "HBS refused the request")
        }
        return try {
            OicpJson.json.decodeFromString<OicpAcknowledgement>(response.body ?: "")
        } catch (e: SerializationException) {
            throw HubException(Hub.HUBJECT, FailureClass.NON_RETRYABLE, null, "answer is not an OICP acknowledgement", e)
        }
    }

    private fun rejection(status: OicpStatusCode): RemoteResult {
        val known = OicpStatus.lookup(status.code)
        if (known == OicpStatus.UNAUTHORIZED_ACCESS) {
            // 017: wrong or expired client certificate. Retrying cannot help; a human must renew it (RH-HUBJECT-ERR-003).
            throw HubException(Hub.HUBJECT, FailureClass.CONFIGURATION, status.code, "certificate or ProviderID rejected (017)")
        }
        return RemoteResult.Rejected(status.code, REJECTIONS[known] ?: RemoteRejection.OTHER, status.additionalInfo)
    }

    private companion object {
        val REJECTIONS = mapOf(
            OicpStatus.NO_VALID_CONTRACT to RemoteRejection.NO_VALID_CONTRACT,
            OicpStatus.PARTNER_NOT_FOUND to RemoteRejection.PARTNER_UNKNOWN,
            OicpStatus.PARTNER_DID_NOT_RESPOND to RemoteRejection.PARTNER_UNREACHABLE,
            OicpStatus.SERVICE_NOT_AVAILABLE to RemoteRejection.PARTNER_UNREACHABLE,
            OicpStatus.EVSE_COMMUNICATION_FAILED to RemoteRejection.EVSE_UNREACHABLE,
            OicpStatus.NO_EV_CONNECTED to RemoteRejection.NO_VEHICLE_CONNECTED,
            OicpStatus.EVSE_ALREADY_RESERVED to RemoteRejection.EVSE_RESERVED,
            OicpStatus.EVSE_IN_USE to RemoteRejection.EVSE_IN_USE,
            OicpStatus.UNKNOWN_EVSE_ID to RemoteRejection.EVSE_UNKNOWN,
            OicpStatus.EVSE_NOT_HUBJECT_COMPATIBLE to RemoteRejection.EVSE_NOT_ROAMABLE,
            OicpStatus.EVSE_OUT_OF_SERVICE to RemoteRejection.EVSE_OUT_OF_SERVICE,
        )
    }
}
