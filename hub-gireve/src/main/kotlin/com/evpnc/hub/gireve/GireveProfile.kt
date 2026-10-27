package com.evpnc.hub.gireve

import com.evpnc.hub.core.ocpi221.OcpiParty
import com.evpnc.hub.core.profile.ProfileDocument
import com.evpnc.hub.core.profile.Profiles
import com.evpnc.hub.core.resilience.FixedSchedule
import java.time.Duration

/** Typed view on `gireve-ocpi-2.2.1.profile.yaml`. */
class GireveProfile(val document: ProfileDocument) {
    private val countryCode = document.string("hub.country_code")

    fun hubParty(environment: GireveEnvironment): OcpiParty = OcpiParty(
        countryCode,
        document.string(if (environment == GireveEnvironment.PRODUCTION) "hub.party_id.production" else "hub.party_id.pre_production"),
    )

    val uidByteLengths: List<Int> = document.stringList("tokens.rfid_uid.bytes").map { it.toInt() }
    val locationReferenceRequired: Boolean = document.bool("tokens.authorize.location_reference_required")
    val unsupportedCommands: Set<String> = document.stringList("commands.not_supported").toSet()
    val supportedModules: Set<String> = document.stringList("modules.supported").toSet()
    val meterUpdateMinInterval: Duration = Duration.ofSeconds(document.int("sessions.meter_update_min_interval_seconds").toLong())

    /** Store-and-forward retry for sessions and CDRs we send: 5, 10, 20 minutes in the guide's example. */
    val retrySchedule = FixedSchedule(document.stringList("reliability.backoff_example_minutes").map { Duration.ofMinutes(it.toLong()) })

    /** HTTP statuses that mean "wait and try again" (425, 429). */
    val waitStatuses: Set<Int> = document.stringList("reliability.http_wait_codes").map { it.toInt() }.toSet()

    companion object {
        const val FILE = "gireve-ocpi-2.2.1.profile.yaml"

        fun load() = GireveProfile(Profiles.fromClasspath(FILE))
    }
}
