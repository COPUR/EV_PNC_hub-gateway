package com.evpnc.hub.gireve;

import com.evpnc.hub.core.HttpRequestSpec;
import com.evpnc.hub.core.Json;
import com.evpnc.hub.core.ocpi221.Ocpi221Compat;
import com.evpnc.hub.core.ocpi221.OcpiHeaders;
import com.evpnc.hub.core.ocpi221.OcpiObject;
import com.evpnc.hub.core.ocpi221.OcpiParty;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds token pushes to the IOP. GIREVE implements PUT only (no PATCH, no pull), does not take Plug&amp;Charge tokens
 * (they are authorised in real time by eMAID), and wants RFID UIDs normalised (RH-GIREVE-TOK-002..004).
 */
public final class GireveTokenPublisher {
    private final GireveConfig config;
    private final GireveProfile profile;
    private final Ocpi221Compat compat;

    public GireveTokenPublisher(GireveConfig config, GireveProfile profile, Ocpi221Compat compat) {
        this.config = config;
        this.profile = profile;
        this.compat = compat;
    }

    /** @return the request, or null when this token must not be pushed to GIREVE. */
    public HttpRequestSpec putRequest(ObjectNode token, String requestId, String correlationId) {
        String type = Json.text(token, "type");
        if ("EMAID".equals(type)) {
            return null;
        }
        String uid = Json.text(token, "uid");
        if (uid == null) {
            return null;
        }
        String normalisedUid = "RFID".equals(type) ? RfidUid.normalise(uid, profile.uidByteLengths()) : uid;
        if (normalisedUid == null) {
            return null;
        }
        ObjectNode withUid = token.deepCopy();
        withUid.put("uid", normalisedUid);
        ObjectNode body = compat.outbound(OcpiObject.TOKEN, withUid);
        OcpiParty hub = profile.hubParty(config.environment());
        return new HttpRequestSpec(
                "PUT",
                config.tokensPath() + "/" + config.ourParty().countryCode() + "/" + config.ourParty().partyId() + "/" + normalisedUid,
                OcpiHeaders.request("tokens", config.ourParty(), hub, config.tokenForHub().reveal(), requestId, correlationId),
                body.toString());
    }
}
