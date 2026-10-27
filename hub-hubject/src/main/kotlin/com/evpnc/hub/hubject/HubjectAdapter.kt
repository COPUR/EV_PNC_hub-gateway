package com.evpnc.hub.hubject

import com.evpnc.hub.core.AuthorizationPolicy
import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.HubAdapter
import com.evpnc.hub.core.HubCapability
import com.evpnc.hub.core.HubTransport
import com.evpnc.hub.core.CdrSink
import com.evpnc.hub.core.RemoteSessionPort
import com.evpnc.hub.core.profile.ProfileDocument
import com.evpnc.hub.core.resilience.CdrIntake
import com.evpnc.hub.core.resilience.GuardedPolicy
import com.evpnc.hub.core.resilience.IdempotencyStore
import com.evpnc.hub.core.resilience.InMemoryIdempotencyStore
import com.evpnc.hub.core.resilience.Throttler
import java.time.Clock
import kotlinx.serialization.encodeToString

/**
 * Hubject as an online EMP (OICP 2.3). Inbound calls from the HBS arrive as JSON text at [authorizeStart] and
 * [chargeDetailRecord], which a web layer maps to `POST /charging/v21/operators/{operatorID}/authorize/start` and
 * `POST /cdrmgmt/v22/operators/{operatorID}/charge-detail-record`. Outbound commands go through [RemoteSessionPort].
 *
 * Not yet implemented, and therefore not in [capabilities]: CDR reconciliation (GetChargeDetailRecords), EVSE data and
 * status pulls, charging notifications, dynamic pricing, and the OPCP Plug&Charge contract clients. See the card list in
 * docs/adr/0018-emsp-hub-adapter-framework.md.
 */
class HubjectAdapter(
    providerId: String,
    policy: AuthorizationPolicy,
    cdrSink: CdrSink,
    transport: HubTransport,
    clock: Clock = Clock.systemUTC(),
    idempotency: IdempotencyStore = InMemoryIdempotencyStore(),
    private val hubjectProfile: HubjectProfile = HubjectProfile.load(),
    statusMapping: AuthorizeStatusMapping = AuthorizeStatusMapping.load(),
) : HubAdapter, RemoteSessionPort by HubjectRemoteClient(providerId, transport, Throttler(hubjectProfile.rateLimitsPerMinute, clock)) {

    override val hub = Hub.HUBJECT
    override val capabilities = setOf(
        HubCapability.ONLINE_AUTHORIZE,
        HubCapability.REMOTE_START,
        HubCapability.REMOTE_STOP,
        HubCapability.CDR_RECEIVE,
    )
    override val profile: ProfileDocument = hubjectProfile.document

    val cdrIntake = CdrIntake(cdrSink, idempotency)
    private val authorize = HubjectAuthorizeHandler(providerId, GuardedPolicy(policy), hubjectProfile, statusMapping, clock)
    private val cdr = HubjectCdrHandler(cdrIntake)

    fun authorizeStart(body: String): String {
        val request = try {
            OicpJson.json.decodeFromString<AuthorizeStartRequest>(body)
        } catch (_: kotlinx.serialization.SerializationException) {
            return OicpJson.json.encodeToString(
                AuthorizeStartResponse(
                    HubjectAuthorizeHandler.NOT_AUTHORIZED,
                    HubjectAuthorizeHandler.statusCode(OicpStatus.DATA_ERROR, "request is not an eRoamingAuthorizeStart"),
                ),
            )
        }
        return OicpJson.json.encodeToString(authorize.handle(request))
    }

    fun chargeDetailRecord(body: String): String {
        val record = try {
            OicpJson.json.decodeFromString<OicpChargeDetailRecord>(body)
        } catch (_: kotlinx.serialization.SerializationException) {
            return OicpJson.json.encodeToString(
                OicpAcknowledgement(false, HubjectAuthorizeHandler.statusCode(OicpStatus.DATA_ERROR, "request is not a charge detail record")),
            )
        }
        return OicpJson.json.encodeToString(cdr.handle(record))
    }
}
