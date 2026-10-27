package com.evpnc.hub.gireve

import com.evpnc.hub.core.Secret
import com.evpnc.hub.core.ocpi221.OcpiParty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val config = GireveConfig(
    environment = GireveEnvironment.PRE_PRODUCTION,
    ourParty = OcpiParty("TR", "ZZZ"),
    tokenForHub = Secret("hub-secret"),
    callbackBase = "https://emsp.example/ocpi/2.2.1/commands",
)

fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
