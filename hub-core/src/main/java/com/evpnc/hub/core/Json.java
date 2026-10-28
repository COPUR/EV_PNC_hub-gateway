package com.evpnc.hub.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The JSON seam of the framework: Jackson trees, so adapters do not depend on a generated model. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    public static ArrayNode array() {
        return JsonNodeFactory.instance.arrayNode();
    }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    public static ObjectNode parseObject(String text) {
        JsonNode node = parse(text);
        if (node instanceof ObjectNode object) {
            return object;
        }
        throw new IllegalArgumentException("JSON object expected");
    }

    public static String write(JsonNode node) {
        return node.toString();
    }

    /** The scalar text of {@code node.get(field)}, or null when absent, null or not a scalar. */
    public static String text(JsonNode node, String field) {
        return scalar(node == null ? null : node.get(field));
    }

    /** The scalar text of {@code node}, or null when absent, null or not a scalar. */
    public static String scalar(JsonNode node) {
        return node != null && node.isValueNode() && !node.isNull() ? node.asText() : null;
    }

    /** Like {@link #scalar} but never null. */
    public static String textOrEmpty(JsonNode node) {
        String value = scalar(node);
        return value == null ? "" : value;
    }
}
