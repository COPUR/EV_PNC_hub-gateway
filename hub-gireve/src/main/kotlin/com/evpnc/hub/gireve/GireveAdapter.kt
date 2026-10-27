package com.evpnc.hub.gireve

import com.evpnc.hub.core.AuthorizationPolicy
import com.evpnc.hub.core.CdrSink
import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.HubAdapter
import com.evpnc.hub.core.HubCapability
import com.evpnc.hub.core.HubTransport
import com.evpnc.hub.core.RemoteSessionPort
import com.evpnc.hub.core.ocpi221.ConversionTable
import com.evpnc.hub.core.ocpi221.Ocpi221Compat
import com.evpnc.hub.core.ocpi221.Ocpi221Config
import com.evpnc.hub.core.profile.ProfileDocument
import com.evpnc.hub.core.profile.Profiles
import com.evpnc.hub.core.resilience.CdrIntake
import com.evpnc.hub.core.resilience.GuardedPolicy
import com.evpnc.hub.core.resilience.IdempotencyStore
import com.evpnc.hub.core.resilience.InMemoryIdempotencyStore
import java.time.Clock

/**
 * GIREVE as an OCPI 2.2.1 IOP partner (BOOST!/OPEN! roaming). Wired for the Europe expansion; the pilot uses GIREVE only for
 * Plug&Charge pre-production (ADR-0005), so nothing here talks to GIREVE until a contract exists.
 *
 * Inbound (the IOP calls us): [authorize] for `tokens/{uid}/authorize`, [receiveCdr] for `cdrs`.
 * Outbound: [RemoteSessionPort] for START/STOP_SESSION, [tokenPublisher] for token PUTs.
 *
 * Not yet implemented, and therefore not in [capabilities]: registration (FromIOP credentials), location and tariff ingest,
 * session ingest, and the PNCP Plug&Charge client. See docs/adr/0018-emsp-hub-adapter-framework.md.
 */
class GireveAdapter(
    config: GireveConfig,
    policy: AuthorizationPolicy,
    cdrSink: CdrSink,
    transport: HubTransport,
    clock: Clock = Clock.systemUTC(),
    idempotency: IdempotencyStore = InMemoryIdempotencyStore(),
    private val gireveProfile: GireveProfile = GireveProfile.load(),
) : HubAdapter, RemoteSessionPort {

    private val hubParty = gireveProfile.hubParty(config.environment)
    val compat = Ocpi221Compat(Hub.GIREVE, Ocpi221Config(hubParty.countryCode, hubParty.partyId))

    init {
        // Fail at start-up, not in production, when the vendored table has rules this code does not know.
        ConversionTable.from(Profiles.fromClasspath(CONVERSION_TABLE)).verifyCovered(Ocpi221Compat.COVERAGE.keys)
    }

    override val hub = Hub.GIREVE
    override val capabilities = setOf(
        HubCapability.ONLINE_AUTHORIZE,
        HubCapability.REMOTE_START,
        HubCapability.REMOTE_STOP,
        HubCapability.CDR_RECEIVE,
        HubCapability.TOKEN_PUSH,
    )
    override val profile: ProfileDocument = gireveProfile.document

    val cdrIntake = CdrIntake(cdrSink, idempotency)
    private val authorizeHandler = GireveAuthorizeHandler(config, gireveProfile, compat, GuardedPolicy(policy), clock)
    private val cdrHandler = GireveCdrHandler(compat, cdrIntake, clock)
    private val commands = GireveCommandClient(config, gireveProfile, compat, transport, clock)
    val tokenPublisher = GireveTokenPublisher(config, gireveProfile, compat)

    fun authorize(tokenUid: String, type: String?, body: String?): OcpiResponse = authorizeHandler.handle(tokenUid, type, body)

    fun receiveCdr(body: String): OcpiResponse = cdrHandler.handle(body)

    override fun start(command: com.evpnc.hub.core.RemoteStart) = commands.start(command)

    override fun stop(command: com.evpnc.hub.core.RemoteStop) = commands.stop(command)

    companion object {
        const val CONVERSION_TABLE = "ocpi-2.3.0-to-2.2.1.conversion.yaml"
    }
}
