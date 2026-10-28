package com.evpnc.hub.core.ocpi221;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/**
 * @param sideData facts the canonical model has no field for, keyed by what they describe (for example
 *     {@code gireve_id:/evses/0}). The caller stores them next to the object; nothing is silently dropped.
 */
public record Converted(ObjectNode json, Map<String, String> sideData) {
    public Converted {
        sideData = Map.copyOf(sideData);
    }
}
