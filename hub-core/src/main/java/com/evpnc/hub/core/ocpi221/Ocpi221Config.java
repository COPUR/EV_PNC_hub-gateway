package com.evpnc.hub.core.ocpi221;

/**
 * @param hubCountryCode the hub's own OCPI identity, FR/107 pre-production and FR/007 production for GIREVE
 * @param hubPartyId see above
 * @param taxIncludedFallback value for the canonical {@code tax_included} when a 2.2.1 tariff says nothing. The
 *     conversion table asks for UNKNOWN, which the canonical enum (YES, NO, N/A) does not have yet; N/A is the closest
 *     legal value until ocpi-model adds it.
 */
public record Ocpi221Config(String hubCountryCode, String hubPartyId, String taxIncludedFallback) {
    public Ocpi221Config(String hubCountryCode, String hubPartyId) {
        this(hubCountryCode, hubPartyId, "N/A");
    }
}
