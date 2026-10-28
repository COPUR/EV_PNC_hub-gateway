package com.evpnc.hub.hubject;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON for the OICP wire records. Unknown fields are ignored on input, as OICP expects partners to tolerate additions;
 * null fields are left out on output.
 */
public final class OicpJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private OicpJson() {}

    /** The body is not a valid instance of the wire record. */
    public static final class WireException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        WireException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static <T> T read(String body, Class<T> type) {
        try {
            T value = MAPPER.readValue(body, type);
            if (value == null) {
                throw new WireException("empty body", null);
            }
            return value;
        } catch (JsonProcessingException e) {
            throw new WireException(e.getOriginalMessage(), e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + value.getClass().getSimpleName(), e);
        }
    }
}
