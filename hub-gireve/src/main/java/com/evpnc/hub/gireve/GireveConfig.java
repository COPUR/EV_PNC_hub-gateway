package com.evpnc.hub.gireve;

import com.evpnc.hub.core.Secret;
import com.evpnc.hub.core.ocpi221.OcpiParty;
import java.time.Duration;

/**
 * @param environment pre-production or production
 * @param ourParty our OCPI identity at the hub (country code and party id as GIREVE assigned them)
 * @param tokenForHub the token the hub uses to call us
 * @param callbackBase where GIREVE sends command results: our {@code commands} callback base URL
 * @param authorizeBudget how long the policy gets for a real-time token authorize. GIREVE does not publish the value its
 *     platform waits (Unknown in the hub analysis, backlog GIREVE question), so 8 s is an assumption, not a fact.
 * @param issuer name shown as token issuer
 */
public record GireveConfig(
        GireveEnvironment environment,
        OcpiParty ourParty,
        Secret tokenForHub,
        String callbackBase,
        String tokensPath,
        String commandsPath,
        Duration authorizeBudget,
        String issuer) {

    public GireveConfig(GireveEnvironment environment, OcpiParty ourParty, Secret tokenForHub, String callbackBase) {
        this(environment, ourParty, tokenForHub, callbackBase, "/ocpi/2.2.1/tokens", "/ocpi/2.2.1/commands",
                Duration.ofSeconds(8), "EV_PNC");
    }

    public GireveConfig withAuthorizeBudget(Duration budget) {
        return new GireveConfig(environment, ourParty, tokenForHub, callbackBase, tokensPath, commandsPath, budget, issuer);
    }
}
