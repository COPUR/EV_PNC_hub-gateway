package com.evpnc.hub.gireve;

import com.evpnc.hub.core.Json;
import com.evpnc.hub.core.Secret;
import com.evpnc.hub.core.ocpi221.OcpiParty;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class TestSupport {
    static final GireveConfig CONFIG = new GireveConfig(
            GireveEnvironment.PRE_PRODUCTION,
            new OcpiParty("TR", "ZZZ"),
            new Secret("hub-secret"),
            "https://emsp.example/ocpi/2.2.1/commands");

    private TestSupport() {}

    static ObjectNode json(String text) {
        return Json.parseObject(text);
    }
}
