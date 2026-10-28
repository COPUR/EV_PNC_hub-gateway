package com.evpnc.hub.gireve;

import com.evpnc.hub.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

/**
 * The OCPI response envelope: {@code data}, {@code status_code}, {@code status_message}, {@code timestamp}.
 *
 * @param message may be null
 * @param data may be null
 */
public record OcpiResponse(int statusCode, String message, JsonNode data, Instant timestamp) {

    public String toJson() {
        ObjectNode out = Json.object();
        if (data != null) {
            out.set("data", data);
        }
        out.put("status_code", statusCode);
        if (message != null) {
            out.put("status_message", message);
        }
        out.put("timestamp", timestamp.toString());
        return out.toString();
    }

    /** @return the parsed envelope, or null when the text is not an OCPI response */
    public static OcpiResponse parse(String text) {
        JsonNode root;
        try {
            root = Json.parse(text == null ? "" : text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode code = root.get("status_code");
        if (code == null || !code.isValueNode() || !code.canConvertToInt() || code.isTextual() && !isInt(code.asText())) {
            return null;
        }
        return new OcpiResponse(code.asInt(), Json.text(root, "status_message"), root.get("data"), Instant.EPOCH);
    }

    private static boolean isInt(String text) {
        try {
            Integer.parseInt(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
