package com.evpnc.hub.hubject

import com.evpnc.hub.core.AuthorizeDecision
import com.evpnc.hub.core.AuthorizeRequest
import com.evpnc.hub.core.DecisionReason
import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.resilience.GuardedDecision
import com.evpnc.hub.core.resilience.GuardedPolicy
import com.evpnc.hub.core.resilience.TimeBudget
import java.time.Clock
import java.time.Duration

/**
 * Answers Hubject's eRoamingAuthorizeStart as an online EMP (ADR-0002). Hubject waits [HubjectProfile.authorizeResponseTimeout]
 * and treats an unreachable EMP as NotAuthorized, so a slow policy engine must not cost the whole window: the policy gets the
 * window minus [safetyMargin] (network, serialisation) and a missed budget is answered NotAuthorized with the configured
 * fallback code (RH-HUBJECT-AUTH-004).
 */
class HubjectAuthorizeHandler(
    private val providerId: String,
    private val policy: GuardedPolicy,
    private val profile: HubjectProfile,
    private val statusMapping: AuthorizeStatusMapping,
    private val clock: Clock,
    private val safetyMargin: Duration = Duration.ofSeconds(2),
) {
    init {
        require(safetyMargin < profile.authorizeResponseTimeout) { "safety margin must be shorter than the hub's window" }
    }

    fun handle(request: AuthorizeStartRequest): AuthorizeStartResponse {
        val budget = TimeBudget.starting(clock, profile.authorizeResponseTimeout.minus(safetyMargin))
        val receivedAt = clock.instant()

        val identified = when (val result = HubjectMapping.identify(request.identification)) {
            is HubjectMapping.IdentificationResult.Ok -> result.identified
            is HubjectMapping.IdentificationResult.Invalid -> {
                // A UID or EvcoID that breaks the contract pattern is "invalid UID / invalid credentials" (102, 101, 105).
                val status = if (result.channel == null) statusMapping.malformedRequest
                else statusMapping.forReason(DecisionReason.TOKEN_UNKNOWN, result.channel)
                return refusal(request, status, result.problem)
            }
        }
        if (request.sessionId != null && !HubjectMapping.SESSION_ID.matches(request.sessionId)) {
            return refusal(request, statusMapping.malformedRequest, "SessionID breaks the contract pattern")
        }

        val platformRequest = AuthorizeRequest(
            hub = Hub.HUBJECT,
            requestId = request.sessionId ?: request.cpoPartnerSessionId ?: "hubject-${receivedAt.toEpochMilli()}",
            channel = identified.channel,
            token = identified.token,
            receivedAt = receivedAt,
            evseId = request.evseId,
            operatorId = request.operatorId,
            hubSessionId = request.sessionId,
            pin = identified.pin,
        )

        return when (val guarded = policy.decide(platformRequest, budget)) {
            is GuardedDecision.Decided -> answer(request, platformRequest, guarded.decision)
            GuardedDecision.TimedOut -> refusal(request, statusMapping.policyTimeout, "policy did not answer within the budget")
            is GuardedDecision.PolicyFailed -> refusal(request, statusMapping.policyFailed, "policy failed")
        }
    }

    private fun answer(request: AuthorizeStartRequest, platform: AuthorizeRequest, decision: AuthorizeDecision): AuthorizeStartResponse =
        when (decision) {
            is AuthorizeDecision.Allowed -> AuthorizeStartResponse(
                authorizationStatus = AUTHORIZED,
                statusCode = statusCode(statusMapping.allowed, null),
                sessionId = request.sessionId,
                cpoPartnerSessionId = request.cpoPartnerSessionId,
                empPartnerSessionId = request.empPartnerSessionId,
                providerId = providerId,
                stopIdentifications = decision.stopTokens.takeIf { it.isNotEmpty() }?.map(HubjectMapping::identification),
            )
            is AuthorizeDecision.Denied -> {
                val reason = decision.reasons.firstOrNull() ?: DecisionReason.OTHER
                refusal(request, statusMapping.forReason(reason, platform.channel), decision.reasons.joinToString { it.name })
            }
        }

    private fun refusal(request: AuthorizeStartRequest, status: OicpStatus, info: String) = AuthorizeStartResponse(
        authorizationStatus = NOT_AUTHORIZED,
        statusCode = statusCode(status, info),
        sessionId = request.sessionId,
        cpoPartnerSessionId = request.cpoPartnerSessionId,
        empPartnerSessionId = request.empPartnerSessionId,
        providerId = providerId,
    )

    companion object {
        const val AUTHORIZED = "Authorized"
        const val NOT_AUTHORIZED = "NotAuthorized"

        /** Description only with the defined value; free text goes to AdditionalInfo, cut to the contract's bound. */
        fun statusCode(status: OicpStatus, info: String?) = OicpStatusCode(
            code = status.code,
            description = status.description,
            additionalInfo = info?.take(OicpStatus.MAX_ADDITIONAL_INFO),
        )
    }
}
