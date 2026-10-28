package com.evpnc.hub.core.ocpi221;

import com.evpnc.hub.core.FailureClass;
import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.HubException;
import com.evpnc.hub.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Canonical OCPI 2.3.0 <-> OCPI 2.2.1 as a hub speaks it (ADR-0001, translate at the edge). Works on JSON, so it does
 * not depend on any generated model. Hub rules live in {@code ocpi-2.3.0-to-2.2.1.conversion.yaml} (see
 * {@link ConversionTable}).
 */
public final class Ocpi221Compat {
    private static final List<String> PRICE_FIELDS = List.of(
            "total_cost", "total_fixed_cost", "total_energy_cost", "total_time_cost",
            "total_parking_cost", "total_reservation_cost");
    private static final Set<String> BLOCKED_MODULES = Set.of("payments", "chargingprofiles", "hubclientinfo");
    private static final Set<String> BLOCKED_COMMANDS = Set.of("RESERVE_NOW", "CANCEL_RESERVATION", "UNLOCK_CONNECTOR");
    private static final Set<String> PLUG_AND_CHARGE_CAPABILITIES =
            Set.of("ISO_15118_2_PLUG_AND_CHARGE", "ISO_15118_20_PLUG_AND_CHARGE");

    /** ISO 15118-1 Annex H eMAID, check digit optional. */
    public static final Pattern EMAID = Pattern.compile("^[A-Za-z]{2}-?[A-Za-z0-9]{3}-?C[A-Za-z0-9]{8}(-?[A-Za-z0-9])?$");

    /**
     * One entry per rule of the conversion table. IMPLEMENTED names the code that applies it; NO_TRANSFORM says why the
     * rule needs no JSON change. {@link ConversionTable#verifyCovered} compares these keys with the table.
     */
    public static final Map<String, String> COVERAGE = coverage();

    private static Map<String, String> coverage() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("VersionNumber", "IMPLEMENTED wireVersion");
        m.put("Credentials.hub_party_id", "IMPLEMENTED inbound/outbound CREDENTIALS");
        m.put("Role=HUB", "IMPLEMENTED inboundCredentials moves the HUB role to side data");
        m.put("Price", "IMPLEMENTED inboundPrice");
        m.put("Tariff.min_price/max_price", "IMPLEMENTED inboundTariff");
        m.put("Tariff.tax_included", "IMPLEMENTED inboundTariff (fallback, see Ocpi221Config)");
        m.put("Tariff.type", "NO_TRANSFORM extra tariff types never arrive; only REGULAR is forwarded");
        m.put("TariffDimensionType=SESSION_TIME", "NO_TRANSFORM passed through as an extension value");
        m.put("Location.help_phone", "NO_TRANSFORM absent means null");
        m.put("Location.address", "NO_TRANSFORM canonical limit widens to 255 in ocpi-model");
        m.put("Location.state", "NO_TRANSFORM canonical limit widens to 45 in ocpi-model");
        m.put("EVSE.parking", "IMPLEMENTED inboundLocation");
        m.put("EVSE.accepted_service_providers", "IMPLEMENTED inboundLocation");
        m.put("Connector.capabilities", "IMPLEMENTED plugAndChargeCapable reads both levels");
        m.put("ConnectorType=[MCS,SAE_J3400]", "NO_TRANSFORM GIREVE encoding unknown; values pass through");
        m.put("ParkingRestriction=[EMPLOYEES,TAXIS,TENANTS]", "NO_TRANSFORM never sent by the hub");
        m.put("Token.type=EMAID", "IMPLEMENTED inboundToken/outboundToken");
        m.put("Token#push", "NO_TRANSFORM transport rule (PUT only, no PATCH, no pull); enforced by the GIREVE adapter");
        m.put("CDR.SignedValue.plain_data", "NO_TRANSFORM canonical limit widens to 5000 in ocpi-model");
        m.put("CDR.{credit,credit_reference_id,home_charging_compensation}", "NO_TRANSFORM the hub never sends them");
        m.put("Location/EVSE/Connector/Tariff.gireve_id", "IMPLEMENTED moveGireveId (side data)");
        m.put("Session.id", "NO_TRANSFORM hub id kept as is; naming rule documented in the GIREVE adapter");
        m.put("CDR.id", "NO_TRANSFORM CPO id kept as is");
        m.put("module:payments", "IMPLEMENTED checkModule");
        m.put("module:chargingprofiles", "IMPLEMENTED checkModule");
        m.put("module:hubclientinfo", "IMPLEMENTED checkModule");
        m.put("Session#charging_preferences", "IMPLEMENTED checkEndpoint");
        m.put("Command=[RESERVE_NOW,CANCEL_RESERVATION,UNLOCK_CONNECTOR]", "IMPLEMENTED checkCommand");
        m.put("StartSession.evse_uid", "IMPLEMENTED outboundStartSession");
        return Map.copyOf(m);
    }

    private final Hub hub;
    private final Ocpi221Config config;

    public Ocpi221Compat(Hub hub, Ocpi221Config config) {
        this.hub = hub;
        this.config = config;
    }

    public Converted inbound(OcpiObject kind, ObjectNode json) {
        Map<String, String> side = new LinkedHashMap<>();
        ObjectNode converted = switch (kind) {
            case CREDENTIALS -> inboundCredentials(json, side);
            case TOKEN -> inboundToken(json);
            case SESSION -> inboundSession(json);
            case CDR -> inboundCdr(json);
            case TARIFF -> inboundTariff(json, side);
            case LOCATION -> inboundLocation(json, side);
            case START_SESSION -> throw new IllegalArgumentException("START_SESSION is outbound only");
        };
        return new Converted(converted, side);
    }

    public ObjectNode outbound(OcpiObject kind, ObjectNode json) {
        return switch (kind) {
            case CREDENTIALS -> {
                ObjectNode copy = json.deepCopy();
                copy.remove("hub_party_id");
                yield copy;
            }
            case TOKEN -> outboundToken(json);
            case START_SESSION -> outboundStartSession(json);
            default -> throw new IllegalArgumentException(kind + " is not sent to the hub by the eMSP");
        };
    }

    /** The version the hub gets in version lists and URLs: 2.3.0 does not exist for it. */
    public String wireVersion(String canonical) {
        return "2.3.0".equals(canonical) ? "2.2.1" : canonical;
    }

    public void checkModule(String module) {
        if (BLOCKED_MODULES.contains(module)) {
            throw refuse("MODULE_NOT_IMPLEMENTED", "module '" + module + "' is not implemented by the hub");
        }
    }

    public void checkEndpoint(String module, String endpoint) {
        checkModule(module);
        if ("sessions".equals(module) && "charging_preferences".equals(endpoint)) {
            throw refuse("ENDPOINT_NOT_IMPLEMENTED", "sessions/charging_preferences is not implemented by the hub");
        }
    }

    public void checkCommand(String type) {
        if (BLOCKED_COMMANDS.contains(type)) {
            throw refuse("COMMAND_NOT_SUPPORTED", "command " + type + " is not supported by the hub");
        }
    }

    /** Plug&Charge capability may sit on the EVSE (2.2.1 base) or on the connector (GIREVE guide); look at both. */
    public boolean plugAndChargeCapable(ObjectNode evse) {
        List<String> capabilities = new ArrayList<>(stringValues(evse.get("capabilities")));
        if (evse.get("connectors") instanceof ArrayNode connectors) {
            for (JsonNode connector : connectors) {
                capabilities.addAll(stringValues(connector.get("capabilities")));
            }
        }
        return capabilities.stream().anyMatch(PLUG_AND_CHARGE_CAPABILITIES::contains);
    }

    // ---- inbound (hub -> canonical 2.3.0) ----

    private ObjectNode inboundCredentials(ObjectNode json, Map<String, String> side) {
        ObjectNode out = json.deepCopy();
        if (json.get("roles") instanceof ArrayNode roles) {
            ArrayNode kept = Json.array();
            boolean sideRecorded = false;
            for (JsonNode role : roles) {
                if ("HUB".equals(Json.text(role, "role"))) {
                    if (!sideRecorded) {
                        side.put("hub_role", Json.textOrEmpty(role.get("country_code")) + "/" + Json.textOrEmpty(role.get("party_id")));
                        sideRecorded = true;
                    }
                } else {
                    kept.add(role);
                }
            }
            out.set("roles", kept);
        }
        if (!out.has("hub_party_id")) {
            ObjectNode party = Json.object();
            party.put("country_code", config.hubCountryCode());
            party.put("party_id", config.hubPartyId());
            out.set("hub_party_id", party);
        }
        return out;
    }

    private ObjectNode inboundToken(ObjectNode json) {
        String type = Json.text(json, "type");
        String uid = Json.text(json, "uid");
        if (!"OTHER".equals(type) || uid == null || !EMAID.matcher(uid).matches()) {
            return json;
        }
        ObjectNode out = json.deepCopy();
        out.set("type", TextNode.valueOf("EMAID"));
        if (!out.has("contract_id")) {
            out.set("contract_id", TextNode.valueOf(uid));
        }
        return out;
    }

    private ObjectNode inboundSession(ObjectNode json) {
        return mapFields(json, Map.of("cdr_token", this::inboundToken, "total_cost", this::inboundPrice));
    }

    private ObjectNode inboundCdr(ObjectNode json) {
        Map<String, UnaryOperator<ObjectNode>> transforms = new LinkedHashMap<>();
        transforms.put("cdr_token", this::inboundToken);
        PRICE_FIELDS.forEach(field -> transforms.put(field, this::inboundPrice));
        return mapFields(json, transforms);
    }

    private ObjectNode inboundTariff(ObjectNode json, Map<String, String> side) {
        ObjectNode out = json.deepCopy();
        for (String limit : List.of("min_price", "max_price")) {
            if (!(out.get(limit) instanceof ObjectNode price) || price.has("before_taxes")) {
                continue;
            }
            ObjectNode converted = Json.object();
            if (price.has("excl_vat")) {
                converted.set("before_taxes", price.get("excl_vat"));
            }
            if (price.has("incl_vat")) {
                converted.set("after_taxes", price.get("incl_vat"));
            }
            out.set(limit, converted);
        }
        if (!out.has("tax_included")) {
            out.set("tax_included", TextNode.valueOf(config.taxIncludedFallback()));
            side.put("tax_included", "defaulted");
        }
        moveGireveId(out, "", side);
        return out;
    }

    private ObjectNode inboundLocation(ObjectNode json, Map<String, String> side) {
        ObjectNode out = json.deepCopy();
        moveGireveId(out, "", side);
        if (out.get("evses") instanceof ArrayNode evses) {
            ArrayNode converted = Json.array();
            for (int i = 0; i < evses.size(); i++) {
                ObjectNode evse = ((ObjectNode) evses.get(i)).deepCopy();
                if (!evse.has("parking")) {
                    evse.set("parking", Json.array());
                }
                if (!evse.has("accepted_service_providers")) {
                    evse.set("accepted_service_providers", Json.array());
                }
                moveGireveId(evse, "/evses/" + i, side);
                if (evse.get("connectors") instanceof ArrayNode connectors) {
                    ArrayNode convertedConnectors = Json.array();
                    for (int j = 0; j < connectors.size(); j++) {
                        ObjectNode connector = ((ObjectNode) connectors.get(j)).deepCopy();
                        moveGireveId(connector, "/evses/" + i + "/connectors/" + j, side);
                        convertedConnectors.add(connector);
                    }
                    evse.set("connectors", convertedConnectors);
                }
                converted.add(evse);
            }
            out.set("evses", converted);
        }
        return out;
    }

    private ObjectNode inboundPrice(ObjectNode price) {
        if (!price.has("excl_vat")) {
            return price;
        }
        BigDecimal excl = decimal(price.get("excl_vat"));
        BigDecimal incl = price.has("incl_vat") ? decimal(price.get("incl_vat")) : null;
        ObjectNode out = Json.object();
        out.set("before_taxes", DoubleNode.valueOf(excl.doubleValue()));
        ArrayNode taxes = Json.array();
        if (incl != null) {
            ObjectNode vat = Json.object();
            vat.put("name", "VAT");
            vat.set("amount", DoubleNode.valueOf(incl.subtract(excl).doubleValue()));
            taxes.add(vat);
        }
        out.set("taxes", taxes);
        return out;
    }

    // ---- outbound (canonical 2.3.0 -> hub) ----

    private ObjectNode outboundToken(ObjectNode json) {
        if (!"EMAID".equals(Json.text(json, "type"))) {
            return json;
        }
        String emaid = Json.text(json, "contract_id");
        if (emaid == null) {
            emaid = Json.text(json, "uid");
        }
        if (emaid == null) {
            throw refuse("TOKEN_INVALID", "EMAID token without uid or contract_id");
        }
        ObjectNode out = json.deepCopy();
        out.set("type", TextNode.valueOf("OTHER"));
        out.set("uid", TextNode.valueOf(emaid));
        out.set("contract_id", TextNode.valueOf(emaid));
        return out;
    }

    private ObjectNode outboundStartSession(ObjectNode json) {
        String evseUid = Json.text(json, "evse_uid");
        if (evseUid == null || evseUid.isBlank()) {
            throw refuse("EVSE_UID_REQUIRED", "the hub requires evse_uid on START_SESSION");
        }
        if (json.get("token") instanceof ObjectNode token) {
            ObjectNode out = json.deepCopy();
            out.set("token", outboundToken(token));
            return out;
        }
        return json;
    }

    // ---- helpers ----

    private static void moveGireveId(ObjectNode node, String pointer, Map<String, String> side) {
        JsonNode id = node.remove("gireve_id");
        if (id != null) {
            side.put("gireve_id:" + pointer, Json.textOrEmpty(id));
        }
    }

    private static ObjectNode mapFields(ObjectNode json, Map<String, UnaryOperator<ObjectNode>> transforms) {
        ObjectNode out = json.deepCopy();
        for (Map.Entry<String, UnaryOperator<ObjectNode>> transform : transforms.entrySet()) {
            if (out.get(transform.getKey()) instanceof ObjectNode value) {
                out.set(transform.getKey(), transform.getValue().apply(value));
            }
        }
        return out;
    }

    private HubException refuse(String code, String message) {
        return new HubException(hub, FailureClass.NON_RETRYABLE, code, message);
    }

    private static List<String> stringValues(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node instanceof ArrayNode array) {
            for (JsonNode element : array) {
                String value = Json.scalar(element);
                if (value != null) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    private static BigDecimal decimal(JsonNode node) {
        return new BigDecimal(node.asText());
    }
}
