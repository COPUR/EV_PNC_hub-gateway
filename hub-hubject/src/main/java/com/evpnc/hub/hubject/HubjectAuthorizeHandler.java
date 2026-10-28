package com.evpnc.hub.hubject;

import com.evpnc.hub.core.AuthorizeDecision;
import com.evpnc.hub.core.AuthorizeRequest;
import com.evpnc.hub.core.DecisionReason;
import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.resilience.GuardedDecision;
import com.evpnc.hub.core.resilience.GuardedPolicy;
import com.evpnc.hub.core.resilience.TimeBudget;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Answers Hubject's eRoamingAuthorizeStart as an online EMP (ADR-0002). Hubject waits
 * {@link HubjectProfile#authorizeResponseTimeout()} and treats an unreachable EMP as NotAuthorized, so a slow policy
 * engine must not cost the whole window: the policy gets the window minus {@code safetyMargin} (network,
 * serialisation) and a missed budget is answered NotAuthorized with the configured fallback code (RH-HUBJECT-AUTH-004).
 */
public final class HubjectAuthorizeHandler {
    public static final String AUTHORIZED = "Authorized";
    public static final String NOT_AUTHORIZED = "NotAuthorized";

    private final String providerId;
    private final GuardedPolicy policy;
    private final HubjectProfile profile;
    private final AuthorizeStatusMapping statusMapping;
    private final Clock clock;
    private final Duration safetyMargin;

    public HubjectAuthorizeHandler(
            String providerId, GuardedPolicy policy, HubjectProfile profile, AuthorizeStatusMapping statusMapping, Clock clock) {
        this(providerId, policy, profile, statusMapping, clock, Duration.ofSeconds(2));
    }

    public HubjectAuthorizeHandler(
            String providerId,
            GuardedPolicy policy,
            HubjectProfile profile,
            AuthorizeStatusMapping statusMapping,
            Clock clock,
            Duration safetyMargin) {
        if (safetyMargin.compareTo(profile.authorizeResponseTimeout()) >= 0) {
            throw new IllegalArgumentException("safety margin must be shorter than the hub's window");
        }
        this.providerId = providerId;
        this.policy = policy;
        this.profile = profile;
        this.statusMapping = statusMapping;
        this.clock = clock;
        this.safetyMargin = safetyMargin;
    }

    public OicpWire.AuthorizeStartResponse handle(OicpWire.AuthorizeStartRequest request) {
        TimeBudget budget = TimeBudget.starting(clock, profile.authorizeResponseTimeout().minus(safetyMargin));
        Instant receivedAt = clock.instant();

        HubjectMapping.IdentificationResult result = HubjectMapping.identify(request.identification());
        if (result instanceof HubjectMapping.IdentificationResult.Invalid invalid) {
            // A UID or EvcoID that breaks the contract pattern is "invalid UID / invalid credentials" (102, 101, 105).
            OicpStatus status = invalid.channel() == null
                    ? statusMapping.malformedRequest()
                    : statusMapping.forReason(DecisionReason.TOKEN_UNKNOWN, invalid.channel());
            return refusal(request, status, invalid.problem());
        }
        HubjectMapping.Identified identified = ((HubjectMapping.IdentificationResult.Ok) result).identified();

        if (request.sessionId() != null && !HubjectMapping.SESSION_ID.matcher(request.sessionId()).matches()) {
            return refusal(request, statusMapping.malformedRequest(), "SessionID breaks the contract pattern");
        }

        String requestId = request.sessionId() != null
                ? request.sessionId()
                : request.cpoPartnerSessionId() != null ? request.cpoPartnerSessionId() : "hubject-" + receivedAt.toEpochMilli();
        AuthorizeRequest platformRequest = new AuthorizeRequest(
                Hub.HUBJECT, requestId, identified.channel(), identified.token(), receivedAt,
                request.evseId(), request.operatorId(), request.sessionId(), identified.pin());

        GuardedDecision guarded = policy.decide(platformRequest, budget);
        if (guarded instanceof GuardedDecision.Decided decided) {
            return answer(request, platformRequest, decided.decision());
        }
        if (guarded instanceof GuardedDecision.PolicyFailed) {
            return refusal(request, statusMapping.policyFailed(), "policy failed");
        }
        return refusal(request, statusMapping.policyTimeout(), "policy did not answer within the budget");
    }

    private OicpWire.AuthorizeStartResponse answer(
            OicpWire.AuthorizeStartRequest request, AuthorizeRequest platform, AuthorizeDecision decision) {
        if (decision instanceof AuthorizeDecision.Allowed allowed) {
            List<OicpWire.Identification> stop = allowed.stopTokens().isEmpty()
                    ? null
                    : allowed.stopTokens().stream().map(HubjectMapping::identification).collect(Collectors.toList());
            return new OicpWire.AuthorizeStartResponse(
                    AUTHORIZED,
                    statusCode(statusMapping.allowed(), null),
                    request.sessionId(),
                    request.cpoPartnerSessionId(),
                    request.empPartnerSessionId(),
                    providerId,
                    stop);
        }
        AuthorizeDecision.Denied denied = (AuthorizeDecision.Denied) decision;
        DecisionReason reason = denied.reasons().isEmpty() ? DecisionReason.OTHER : denied.reasons().get(0);
        String info = denied.reasons().stream().map(Enum::name).collect(Collectors.joining(", "));
        return refusal(request, statusMapping.forReason(reason, platform.channel()), info);
    }

    private OicpWire.AuthorizeStartResponse refusal(OicpWire.AuthorizeStartRequest request, OicpStatus status, String info) {
        return new OicpWire.AuthorizeStartResponse(
                NOT_AUTHORIZED,
                statusCode(status, info),
                request.sessionId(),
                request.cpoPartnerSessionId(),
                request.empPartnerSessionId(),
                providerId,
                null);
    }

    /** Description only with the defined value; free text goes to AdditionalInfo, cut to the contract's bound. */
    public static OicpWire.StatusCode statusCode(OicpStatus status, String info) {
        String additional = info == null ? null : info.substring(0, Math.min(info.length(), OicpStatus.MAX_ADDITIONAL_INFO));
        return new OicpWire.StatusCode(status.code(), status.description(), additional);
    }
}
