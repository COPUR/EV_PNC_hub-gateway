package com.evpnc.hub.gireve;

import com.evpnc.hub.core.AuthChannel;
import com.evpnc.hub.core.AuthorizeDecision;
import com.evpnc.hub.core.AuthorizeRequest;
import com.evpnc.hub.core.DecisionOutcome;
import com.evpnc.hub.core.DecisionReason;
import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.Json;
import com.evpnc.hub.core.TokenRef;
import com.evpnc.hub.core.TokenType;
import com.evpnc.hub.core.ocpi221.Ocpi221Compat;
import com.evpnc.hub.core.ocpi221.OcpiObject;
import com.evpnc.hub.core.resilience.GuardedDecision;
import com.evpnc.hub.core.resilience.GuardedPolicy;
import com.evpnc.hub.core.resilience.TimeBudget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/**
 * Answers {@code POST tokens/{uid}/authorize}, which GIREVE (the "IOP") sends us when a roaming driver identifies at a
 * CPO's EVSE (RH-GIREVE-TOK-001, ERR-001, ERR-002). The answer follows OCPI: an unknown token is status 2004 with no
 * data, a known token that is refused is AuthorizationInfo with {@code allowed} BLOCKED, NO_CREDIT or NOT_ALLOWED, and a
 * request without location references is 2002 because GIREVE requires them.
 */
public final class GireveAuthorizeHandler {
    private final GireveConfig config;
    private final GireveProfile profile;
    private final Ocpi221Compat compat;
    private final GuardedPolicy policy;
    private final Clock clock;

    public GireveAuthorizeHandler(GireveConfig config, GireveProfile profile, Ocpi221Compat compat, GuardedPolicy policy, Clock clock) {
        this.config = config;
        this.profile = profile;
        this.compat = compat;
        this.policy = policy;
        this.clock = clock;
    }

    public OcpiResponse handle(String tokenUid, String type, String body) {
        Instant now = clock.instant();

        ObjectNode locationReferences = parseLocationReferences(body);
        if (profile.locationReferenceRequired() && locationReferences == null) {
            return new OcpiResponse(OcpiStatusCodes.NOT_ENOUGH_INFORMATION, "location_references are required", null, now);
        }

        TokenRef token = identify(tokenUid, type);
        if (token == null) {
            return new OcpiResponse(OcpiStatusCodes.UNKNOWN_TOKEN, "Unknown Token", null, now);
        }
        String evseId = null;
        if (locationReferences != null && locationReferences.get("evse_uids") != null && locationReferences.get("evse_uids").isArray()
                && !locationReferences.get("evse_uids").isEmpty()) {
            evseId = Json.scalar(locationReferences.get("evse_uids").get(0));
        }
        AuthorizeRequest request = new AuthorizeRequest(
                Hub.GIREVE,
                "gireve-" + now.toEpochMilli() + "-" + token.uid(),
                AuthChannel.TOKEN_AUTHORIZE,
                token,
                now,
                evseId,
                locationReferences == null ? null : Json.text(locationReferences, "location_id"),
                null,
                null);

        GuardedDecision guarded = policy.decide(request, TimeBudget.starting(clock, config.authorizeBudget()));
        if (guarded instanceof GuardedDecision.Decided decided) {
            return answer(decided.decision(), request, locationReferences, now);
        }
        // OCPI has no "try later" for this call; refusing is the safe answer when we cannot decide.
        return new OcpiResponse(OcpiStatusCodes.SERVER_ERROR, "authorization unavailable", null, now);
    }

    private OcpiResponse answer(AuthorizeDecision decision, AuthorizeRequest request, ObjectNode location, Instant now) {
        if (decision instanceof AuthorizeDecision.Allowed allowed) {
            return new OcpiResponse(OcpiStatusCodes.SUCCESS, "Success",
                    info("ALLOWED", request.token(), location, allowed.authorizationReference(), now), now);
        }
        AuthorizeDecision.Denied denied = (AuthorizeDecision.Denied) decision;
        if (denied.reasons().contains(DecisionReason.TOKEN_UNKNOWN)) {
            return new OcpiResponse(OcpiStatusCodes.UNKNOWN_TOKEN, "Unknown Token", null, now);
        }
        return new OcpiResponse(OcpiStatusCodes.SUCCESS, "Success", info(allowedType(denied.outcome()), request.token(), null, null, now), now);
    }

    private static String allowedType(DecisionOutcome outcome) {
        return switch (outcome) {
            case ALLOWED -> "ALLOWED";
            case BLOCKED -> "BLOCKED";
            case NO_CREDIT -> "NO_CREDIT";
            case NOT_ALLOWED -> "NOT_ALLOWED";
        };
    }

    private ObjectNode info(String allowed, TokenRef token, ObjectNode location, String reference, Instant now) {
        ObjectNode out = Json.object();
        out.put("allowed", allowed);
        out.set("token", compat.outbound(OcpiObject.TOKEN, canonicalToken(config, token, now.toString())));
        // Only the EVSEs the driver may use are returned; with one decision for the location all referenced EVSEs are allowed.
        if ("ALLOWED".equals(allowed) && location != null) {
            out.set("location", location);
        }
        if (reference != null) {
            out.put("authorization_reference", reference.substring(0, Math.min(reference.length(), 36)));
        }
        return out;
    }

    /** Token uid + type as the IOP sent them, converted to the canonical type (OTHER holding an eMAID becomes EMAID). */
    private TokenRef identify(String uid, String type) {
        String wireType = (type == null ? "RFID" : type).toUpperCase(Locale.ROOT);
        String normalised;
        if ("RFID".equals(wireType)) {
            normalised = RfidUid.normalise(uid, profile.uidByteLengths());
        } else {
            normalised = uid.trim().isEmpty() ? null : uid.trim();
        }
        if (normalised == null) {
            return null;
        }
        ObjectNode wire = Json.object();
        wire.put("uid", normalised);
        wire.put("type", wireType);
        ObjectNode converted = compat.inbound(OcpiObject.TOKEN, wire).json();
        TokenType canonicalType;
        try {
            canonicalType = TokenType.valueOf(converted.get("type").asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new TokenRef(normalised, canonicalType, Json.text(converted, "contract_id"));
    }

    private static ObjectNode parseLocationReferences(String body) {
        JsonNode parsed;
        try {
            parsed = Json.parse(body == null ? "" : body);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!(parsed instanceof ObjectNode object)) {
            return null;
        }
        String locationId = Json.text(object, "location_id");
        return locationId != null && !locationId.isBlank() ? object : null;
    }

    /** A canonical OCPI 2.3.0 Token for {@code token}. {@code valid} is true: a token the platform authorises is by definition valid. */
    public static ObjectNode canonicalToken(GireveConfig config, TokenRef token, String lastUpdated) {
        ObjectNode out = Json.object();
        out.put("country_code", config.ourParty().countryCode());
        out.put("party_id", config.ourParty().partyId());
        out.put("uid", token.uid());
        out.put("type", token.type().name());
        out.put("contract_id", token.contractId() != null ? token.contractId() : token.uid());
        out.put("issuer", config.issuer());
        out.put("valid", true);
        out.put("whitelist", "NEVER");
        out.put("last_updated", lastUpdated);
        return out;
    }
}
