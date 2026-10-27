package com.evpnc.hub.gireve

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** OCPI status codes the adapter uses (meanings from the OCPI status_codes chapter). */
object OcpiStatusCodes {
    const val SUCCESS = 1000
    const val CLIENT_ERROR = 2000
    const val INVALID_PARAMETERS = 2001
    const val NOT_ENOUGH_INFORMATION = 2002
    const val UNKNOWN_TOKEN = 2004
    const val SERVER_ERROR = 3000
    const val UNKNOWN_RECEIVER = 4001
    const val FORWARDED_REQUEST_TIMEOUT = 4002
    const val RECEIVER_NOT_CONNECTED = 4003
}

/** The OCPI response envelope: `data`, `status_code`, `status_message`, `timestamp`. */
data class OcpiResponse(val statusCode: Int, val message: String?, val data: JsonElement?, val timestamp: Instant) {
    fun toJson(): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            data?.let { put("data", it) }
            put("status_code", statusCode)
            message?.let { put("status_message", it) }
            put("timestamp", JsonPrimitive(timestamp.toString()))
        },
    )

    companion object {
        fun parse(text: String?): OcpiResponse? {
            val root = runCatching { Json.parseToJsonElement(text ?: "").jsonObject }.getOrNull() ?: return null
            val code = root["status_code"]?.jsonPrimitive?.intOrNull ?: return null
            return OcpiResponse(code, root["status_message"]?.jsonPrimitive?.contentOrNull, root["data"], Instant.EPOCH)
        }
    }
}
