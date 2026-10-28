package com.evpnc.hub.gireve;

import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.HubCdr;
import com.evpnc.hub.core.Json;
import com.evpnc.hub.core.TokenRef;
import com.evpnc.hub.core.TokenType;
import com.evpnc.hub.core.ocpi221.Ocpi221Compat;
import com.evpnc.hub.core.ocpi221.OcpiObject;
import com.evpnc.hub.core.resilience.CdrIntake;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Receives {@code POST cdrs} from the IOP. The IOP stores and forwards, so a CDR can arrive again; it is kept once, keyed
 * by the hub's session id (RH-GIREVE-SESSION-001, ERR-008). The full CDR is converted to the canonical 2.3.0 shape and
 * travels in {@link HubCdr#ocpi()}; the remark prefix says who priced it (ERR-009: {@code [?]} means we price it
 * ourselves).
 */
public final class GireveCdrHandler {
    private final Ocpi221Compat compat;
    private final CdrIntake intake;
    private final Clock clock;

    public GireveCdrHandler(Ocpi221Compat compat, CdrIntake intake, Clock clock) {
        this.compat = compat;
        this.intake = intake;
        this.clock = clock;
    }

    public OcpiResponse handle(String body) {
        Instant now = clock.instant();
        ObjectNode cdr;
        try {
            JsonNode parsed = Json.parse(body);
            if (!(parsed instanceof ObjectNode object)) {
                return new OcpiResponse(OcpiStatusCodes.INVALID_PARAMETERS, "body is not a CDR", null, now);
            }
            cdr = object;
        } catch (IllegalArgumentException e) {
            return new OcpiResponse(OcpiStatusCodes.INVALID_PARAMETERS, "body is not a CDR", null, now);
        }
        String id = Json.text(cdr, "id");
        if (id == null || id.isBlank()) {
            return new OcpiResponse(OcpiStatusCodes.INVALID_PARAMETERS, "id missing", null, now);
        }

        ObjectNode canonical = compat.inbound(OcpiObject.CDR, cdr).json();
        intake.receive(toHubCdr(canonical, id));
        // Positive even when the downstream sink is down: the CDR is parked and replayed (store-and-forward is the IOP's job too).
        return new OcpiResponse(OcpiStatusCodes.SUCCESS, "Success", null, now);
    }

    private static HubCdr toHubCdr(ObjectNode cdr, String id) {
        JsonNode location = cdr.get("cdr_location");
        JsonNode token = cdr.get("cdr_token");

        String sessionId = Json.text(cdr, "session_id");
        if (sessionId == null) {
            sessionId = "cdr:" + Json.text(cdr, "country_code") + "-" + Json.text(cdr, "party_id") + "-" + id;
        }
        String evseId = location != null && location.isObject() ? firstNonNull(Json.text(location, "evse_id"), Json.text(location, "evse_uid")) : null;

        TokenRef tokenRef = null;
        if (token != null && token.isObject() && Json.text(token, "uid") != null) {
            TokenType type;
            try {
                type = TokenType.valueOf(token.get("type").asText());
            } catch (IllegalArgumentException | NullPointerException e) {
                type = TokenType.OTHER;
            }
            tokenRef = new TokenRef(Json.text(token, "uid"), type, Json.text(token, "contract_id"));
        }
        return new HubCdr(
                Hub.GIREVE,
                sessionId,
                evseId,
                tokenRef,
                instant(Json.text(cdr, "start_date_time")),
                instant(Json.text(cdr, "end_date_time")),
                doubleOrNull(cdr.get("total_energy")),
                GireveCdrPricing.classify(Json.text(cdr, "remark")))
                .withOcpi(cdr);
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static Double doubleOrNull(JsonNode node) {
        String text = Json.scalar(node);
        if (text == null) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant instant(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text.endsWith("Z") ? text : text + "Z");
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
