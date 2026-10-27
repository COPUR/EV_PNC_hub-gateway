package com.evpnc.hub.hubject

import com.evpnc.hub.core.profile.ProfileDocument
import com.evpnc.hub.core.profile.Profiles
import java.time.Duration

/** Typed view on `hubject-oicp-2.3-emp.profile.yaml`. The adapter reads rules from here, never from constants. */
class HubjectProfile(val document: ProfileDocument) {
    /** How long Hubject waits for our authorize answer. */
    val authorizeResponseTimeout: Duration = Duration.ofSeconds(document.int("timeouts.authorize_start_response_s.value").toLong())

    val cpoRemoteStartWindow: Duration = Duration.ofSeconds(document.int("timeouts.cpo_remote_start_window_s.value").toLong())
    val cpoRemoteStopWindow: Duration = Duration.ofSeconds(document.int("timeouts.cpo_remote_stop_window_s.value").toLong())

    /**
     * Our client timeout for remote start and stop must exceed the CPO window the HBS waits for (RH-HUBJECT-ERR-005:
     * at least 65 s with the 60 s window). The support page says 40 s, the guide 60 s; the larger one plus 5 s wins.
     */
    val remoteClientTimeout: Duration = maxOf(cpoRemoteStartWindow, cpoRemoteStopWindow).plusSeconds(5)

    val rateLimitsPerMinute: Map<String, Int> =
        document.map("rate_limits_per_minute.values").mapValues { (it.value as Number).toInt() }

    companion object {
        const val FILE = "hubject-oicp-2.3-emp.profile.yaml"

        fun load() = HubjectProfile(Profiles.fromClasspath(FILE))
    }
}
