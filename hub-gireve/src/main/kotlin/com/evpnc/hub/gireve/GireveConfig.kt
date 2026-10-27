package com.evpnc.hub.gireve

import com.evpnc.hub.core.Secret
import com.evpnc.hub.core.ocpi221.OcpiParty
import java.time.Duration

enum class GireveEnvironment { PRE_PRODUCTION, PRODUCTION }

/**
 * @property authorizeBudget how long the policy gets for a real-time token authorize. GIREVE does not publish the value its
 *   platform waits (Unknown in the hub analysis, backlog GIREVE question), so 8 s is an assumption, not a fact.
 */
data class GireveConfig(
    val environment: GireveEnvironment,
    /** Our OCPI identity at the hub (country code and party id as GIREVE assigned them). */
    val ourParty: OcpiParty,
    val tokenForHub: Secret,
    /** Where GIREVE sends command results: our `commands` callback base URL. */
    val callbackBase: String,
    val tokensPath: String = "/ocpi/2.2.1/tokens",
    val commandsPath: String = "/ocpi/2.2.1/commands",
    val authorizeBudget: Duration = Duration.ofSeconds(8),
    /** Name shown as token issuer. */
    val issuer: String = "EV_PNC",
)
