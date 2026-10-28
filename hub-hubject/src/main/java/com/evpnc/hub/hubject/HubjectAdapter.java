package com.evpnc.hub.hubject;

import com.evpnc.hub.core.AuthorizationPolicy;
import com.evpnc.hub.core.CdrSink;
import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.HubAdapter;
import com.evpnc.hub.core.HubCapability;
import com.evpnc.hub.core.HubTransport;
import com.evpnc.hub.core.RemoteResult;
import com.evpnc.hub.core.RemoteSessionPort;
import com.evpnc.hub.core.RemoteStart;
import com.evpnc.hub.core.RemoteStop;
import com.evpnc.hub.core.profile.ProfileDocument;
import com.evpnc.hub.core.resilience.CdrIntake;
import com.evpnc.hub.core.resilience.GuardedPolicy;
import com.evpnc.hub.core.resilience.IdempotencyStore;
import com.evpnc.hub.core.resilience.InMemoryIdempotencyStore;
import com.evpnc.hub.core.resilience.Throttler;
import java.time.Clock;
import java.util.Set;

/**
 * Hubject as an online EMP (OICP 2.3). Inbound calls from the HBS arrive as JSON text at {@link #authorizeStart} and
 * {@link #chargeDetailRecord}, which a web layer maps to {@code POST /charging/v21/operators/{operatorID}/authorize/start}
 * and {@code POST /cdrmgmt/v22/operators/{operatorID}/charge-detail-record}. Outbound commands go through
 * {@link RemoteSessionPort}.
 *
 * <p>Not yet implemented, and therefore not in {@link #capabilities()}: CDR reconciliation (GetChargeDetailRecords),
 * EVSE data and status pulls, charging notifications, dynamic pricing, and the OPCP Plug&amp;Charge contract clients. See
 * the card list in docs/adr/0018-emsp-hub-adapter-framework.md.
 */
public final class HubjectAdapter implements HubAdapter, RemoteSessionPort {
    private final HubjectProfile hubjectProfile;
    private final RemoteSessionPort remote;
    private final CdrIntake cdrIntake;
    private final HubjectAuthorizeHandler authorize;
    private final HubjectCdrHandler cdr;

    public HubjectAdapter(String providerId, AuthorizationPolicy policy, CdrSink cdrSink, HubTransport transport) {
        this(providerId, policy, cdrSink, transport, Clock.systemUTC(), new InMemoryIdempotencyStore(),
                HubjectProfile.load(), AuthorizeStatusMapping.load());
    }

    public HubjectAdapter(
            String providerId,
            AuthorizationPolicy policy,
            CdrSink cdrSink,
            HubTransport transport,
            Clock clock,
            IdempotencyStore idempotency,
            HubjectProfile hubjectProfile,
            AuthorizeStatusMapping statusMapping) {
        this.hubjectProfile = hubjectProfile;
        this.remote = new HubjectRemoteClient(providerId, transport, new Throttler(hubjectProfile.rateLimitsPerMinute(), clock));
        this.cdrIntake = new CdrIntake(cdrSink, idempotency);
        this.authorize = new HubjectAuthorizeHandler(providerId, new GuardedPolicy(policy), hubjectProfile, statusMapping, clock);
        this.cdr = new HubjectCdrHandler(cdrIntake);
    }

    @Override
    public Hub hub() {
        return Hub.HUBJECT;
    }

    @Override
    public Set<HubCapability> capabilities() {
        return Set.of(HubCapability.ONLINE_AUTHORIZE, HubCapability.REMOTE_START, HubCapability.REMOTE_STOP, HubCapability.CDR_RECEIVE);
    }

    @Override
    public ProfileDocument profile() {
        return hubjectProfile.document();
    }

    public CdrIntake cdrIntake() {
        return cdrIntake;
    }

    @Override
    public RemoteResult start(RemoteStart command) {
        return remote.start(command);
    }

    @Override
    public RemoteResult stop(RemoteStop command) {
        return remote.stop(command);
    }

    public String authorizeStart(String body) {
        OicpWire.AuthorizeStartRequest request;
        try {
            request = OicpJson.read(body, OicpWire.AuthorizeStartRequest.class);
        } catch (OicpJson.WireException e) {
            return OicpJson.write(new OicpWire.AuthorizeStartResponse(
                    HubjectAuthorizeHandler.NOT_AUTHORIZED,
                    HubjectAuthorizeHandler.statusCode(OicpStatus.DATA_ERROR, "request is not an eRoamingAuthorizeStart")));
        }
        return OicpJson.write(authorize.handle(request));
    }

    public String chargeDetailRecord(String body) {
        OicpWire.ChargeDetailRecord record;
        try {
            record = OicpJson.read(body, OicpWire.ChargeDetailRecord.class);
        } catch (OicpJson.WireException e) {
            return OicpJson.write(new OicpWire.Acknowledgement(
                    false, HubjectAuthorizeHandler.statusCode(OicpStatus.DATA_ERROR, "request is not a charge detail record")));
        }
        return OicpJson.write(cdr.handle(record));
    }
}
