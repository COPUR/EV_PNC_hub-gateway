package com.evpnc.hub.core.ocpi221

import java.util.Base64

data class OcpiParty(val countryCode: String, val partyId: String)

/**
 * OCPI request headers as a hub wants them: routing headers on every call except `credentials` and `versions`,
 * request and correlation ids echoed back on responses, and the token Base64-encoded in `Authorization`.
 */
object OcpiHeaders {
    private val NO_ROUTING = setOf("credentials", "versions")

    fun request(
        module: String,
        from: OcpiParty,
        to: OcpiParty,
        token: String,
        requestId: String,
        correlationId: String,
    ): Map<String, String> = buildMap {
        put("Authorization", "Token " + Base64.getEncoder().encodeToString(token.toByteArray()))
        put("X-Request-ID", requestId)
        put("X-Correlation-ID", correlationId)
        if (module !in NO_ROUTING) {
            put("OCPI-from-country-code", from.countryCode)
            put("OCPI-from-party-id", from.partyId)
            put("OCPI-to-country-code", to.countryCode)
            put("OCPI-to-party-id", to.partyId)
        }
    }

    /** A response must carry the ids of its request unchanged. @return what is wrong, empty when fine. */
    fun checkEcho(request: Map<String, String>, response: Map<String, String>): List<String> =
        listOf("X-Request-ID", "X-Correlation-ID").mapNotNull { name ->
            val sent = request.entries.firstOrNull { it.key.equals(name, true) }?.value
            val got = response.entries.firstOrNull { it.key.equals(name, true) }?.value
            if (sent != null && sent != got) "$name not echoed (sent '$sent', got '${got ?: "nothing"}')" else null
        }
}
