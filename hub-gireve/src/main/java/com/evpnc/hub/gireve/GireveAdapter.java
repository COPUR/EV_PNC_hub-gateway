package com.evpnc.hub.gireve;

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
import com.evpnc.hub.core.ocpi221.ConversionTable;
import com.evpnc.hub.core.ocpi221.Ocpi221Compat;
import com.evpnc.hub.core.ocpi221.Ocpi221Config;
import com.evpnc.hub.core.ocpi221.OcpiParty;
import com.evpnc.hub.core.profile.ProfileDocument;
import com.evpnc.hub.core.profile.Profiles;
import com.evpnc.hub.core.resilience.CdrIntake;
import com.evpnc.hub.core.resilience.GuardedPolicy;
import com.evpnc.hub.core.resilience.IdempotencyStore;
import com.evpnc.hub.core.resilience.InMemoryIdempotencyStore;
import java.time.Clock;
import java.util.Set;

/**
 * GIREVE as an OCPI 2.2.1 IOP partner (BOOST!/OPEN! roaming). Wired for the Europe expansion; the pilot uses GIREVE only
 * for Plug&amp;Charge pre-production (ADR-0005), so nothing here talks to GIREVE until a contract exists.
 *
 * <p>Inbound (the IOP calls us): {@link #authorize} for {@code tokens/{uid}/authorize}, {@link #receiveCdr} for
 * {@code cdrs}. Outbound: {@link RemoteSessionPort} for START/STOP_SESSION, {@link #tokenPublisher()} for token PUTs.
 *
 * <p>Not yet implemented, and therefore not in {@link #capabilities()}: registration (FromIOP credentials), location and
 * tariff ingest, session ingest, and the PNCP Plug&amp;Charge client. See docs/adr/0018-emsp-hub-adapter-framework.md.
 */
public final class GireveAdapter implements HubAdapter, RemoteSessionPort {
    public static final String CONVERSION_TABLE = "ocpi-2.3.0-to-2.2.1.conversion.yaml";

    private final GireveProfile gireveProfile;
    private final Ocpi221Compat compat;
    private final CdrIntake cdrIntake;
    private final GireveAuthorizeHandler authorizeHandler;
    private final GireveCdrHandler cdrHandler;
    private final GireveCommandClient commands;
    private final GireveTokenPublisher tokenPublisher;

    public GireveAdapter(GireveConfig config, AuthorizationPolicy policy, CdrSink cdrSink, HubTransport transport, Clock clock) {
        this(config, policy, cdrSink, transport, clock, new InMemoryIdempotencyStore(), GireveProfile.load());
    }

    public GireveAdapter(
            GireveConfig config,
            AuthorizationPolicy policy,
            CdrSink cdrSink,
            HubTransport transport,
            Clock clock,
            IdempotencyStore idempotency,
            GireveProfile gireveProfile) {
        this.gireveProfile = gireveProfile;
        OcpiParty hubParty = gireveProfile.hubParty(config.environment());
        this.compat = new Ocpi221Compat(Hub.GIREVE, new Ocpi221Config(hubParty.countryCode(), hubParty.partyId()));
        // Fail at start-up, not in production, when the vendored table has rules this code does not know.
        ConversionTable.from(Profiles.fromClasspath(CONVERSION_TABLE)).verifyCovered(Ocpi221Compat.COVERAGE.keySet());
        this.cdrIntake = new CdrIntake(cdrSink, idempotency);
        this.authorizeHandler = new GireveAuthorizeHandler(config, gireveProfile, compat, new GuardedPolicy(policy), clock);
        this.cdrHandler = new GireveCdrHandler(compat, cdrIntake, clock);
        this.commands = new GireveCommandClient(config, gireveProfile, compat, transport, clock);
        this.tokenPublisher = new GireveTokenPublisher(config, gireveProfile, compat);
    }

    @Override
    public Hub hub() {
        return Hub.GIREVE;
    }

    @Override
    public Set<HubCapability> capabilities() {
        return Set.of(HubCapability.ONLINE_AUTHORIZE, HubCapability.REMOTE_START, HubCapability.REMOTE_STOP,
                HubCapability.CDR_RECEIVE, HubCapability.TOKEN_PUSH);
    }

    @Override
    public ProfileDocument profile() {
        return gireveProfile.document();
    }

    public Ocpi221Compat compat() {
        return compat;
    }

    public CdrIntake cdrIntake() {
        return cdrIntake;
    }

    public GireveTokenPublisher tokenPublisher() {
        return tokenPublisher;
    }

    public OcpiResponse authorize(String tokenUid, String type, String body) {
        return authorizeHandler.handle(tokenUid, type, body);
    }

    public OcpiResponse receiveCdr(String body) {
        return cdrHandler.handle(body);
    }

    @Override
    public RemoteResult start(RemoteStart command) {
        return commands.start(command);
    }

    @Override
    public RemoteResult stop(RemoteStop command) {
        return commands.stop(command);
    }
}
