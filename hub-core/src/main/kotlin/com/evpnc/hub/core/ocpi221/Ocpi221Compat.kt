package com.evpnc.hub.core.ocpi221

import com.evpnc.hub.core.FailureClass
import com.evpnc.hub.core.Hub
import com.evpnc.hub.core.HubException
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class OcpiObject { CREDENTIALS, TOKEN, SESSION, CDR, TARIFF, LOCATION, START_SESSION }

/**
 * @property sideData facts the canonical model has no field for, keyed by what they describe
 *   (for example `gireve_id:/evses/0`). The caller stores them next to the object; nothing is silently dropped.
 */
data class Converted(val json: JsonObject, val sideData: Map<String, String> = emptyMap())

data class Ocpi221Config(
    /** The hub's own OCPI identity, FR/107 pre-production and FR/007 production for GIREVE. */
    val hubCountryCode: String,
    val hubPartyId: String,
    /**
     * Value for the canonical `tax_included` when a 2.2.1 tariff says nothing. The conversion table asks for UNKNOWN,
     * which the canonical enum (YES, NO, N/A) does not have yet; N/A is the closest legal value until ocpi-model adds it.
     */
    val taxIncludedFallback: String = "N/A",
)

/**
 * Canonical OCPI 2.3.0 <-> OCPI 2.2.1 as a hub speaks it (ADR-0001, translate at the edge). Works on JSON, so it does
 * not depend on any generated model. Hub rules live in `ocpi-2.3.0-to-2.2.1.conversion.yaml` (see [ConversionTable]).
 */
class Ocpi221Compat(private val hub: Hub, private val config: Ocpi221Config) {

    fun inbound(kind: OcpiObject, json: JsonObject): Converted {
        val side = linkedMapOf<String, String>()
        val converted = when (kind) {
            OcpiObject.CREDENTIALS -> inboundCredentials(json, side)
            OcpiObject.TOKEN -> inboundToken(json)
            OcpiObject.SESSION -> inboundSession(json)
            OcpiObject.CDR -> inboundCdr(json)
            OcpiObject.TARIFF -> inboundTariff(json, side)
            OcpiObject.LOCATION -> inboundLocation(json, side)
            OcpiObject.START_SESSION -> throw IllegalArgumentException("START_SESSION is outbound only")
        }
        return Converted(converted, side)
    }

    fun outbound(kind: OcpiObject, json: JsonObject): JsonObject = when (kind) {
        OcpiObject.CREDENTIALS -> JsonObject(json - "hub_party_id")
        OcpiObject.TOKEN -> outboundToken(json)
        OcpiObject.START_SESSION -> outboundStartSession(json)
        else -> throw IllegalArgumentException("$kind is not sent to the hub by the eMSP")
    }

    /** The version the hub gets in version lists and URLs: 2.3.0 does not exist for it. */
    fun wireVersion(canonical: String): String = if (canonical == "2.3.0") "2.2.1" else canonical

    fun checkModule(module: String) {
        if (module in BLOCKED_MODULES) refuse("MODULE_NOT_IMPLEMENTED", "module '$module' is not implemented by the hub")
    }

    fun checkEndpoint(module: String, endpoint: String) {
        checkModule(module)
        if (module == "sessions" && endpoint == "charging_preferences") {
            refuse("ENDPOINT_NOT_IMPLEMENTED", "sessions/charging_preferences is not implemented by the hub")
        }
    }

    fun checkCommand(type: String) {
        if (type in BLOCKED_COMMANDS) refuse("COMMAND_NOT_SUPPORTED", "command $type is not supported by the hub")
    }

    /** Plug&Charge capability may sit on the EVSE (2.2.1 base) or on the connector (GIREVE guide); look at both. */
    fun plugAndChargeCapable(evse: JsonObject): Boolean {
        val evseCaps = evse["capabilities"].stringValues()
        val connectorCaps = (evse["connectors"] as? JsonArray).orEmpty().flatMap { it.jsonObject["capabilities"].stringValues() }
        return (evseCaps + connectorCaps).any { it in PLUG_AND_CHARGE_CAPABILITIES }
    }

    // ---- inbound (hub -> canonical 2.3.0) ----

    private fun inboundCredentials(json: JsonObject, side: MutableMap<String, String>): JsonObject {
        val roles = json["roles"] as? JsonArray
        val kept = roles?.filterNot { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "HUB" }
        roles?.firstOrNull { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "HUB" }?.let {
            val role = it.jsonObject
            side["hub_role"] = "${role["country_code"].text()}/${role["party_id"].text()}"
        }
        return JsonObject(
            json.toMutableMap().apply {
                if (kept != null) put("roles", JsonArray(kept))
                putIfAbsent("hub_party_id", buildJsonObject {
                    put("country_code", config.hubCountryCode)
                    put("party_id", config.hubPartyId)
                })
            },
        )
    }

    private fun inboundToken(json: JsonObject): JsonObject {
        val type = json["type"]?.jsonPrimitive?.contentOrNull
        val uid = json["uid"]?.jsonPrimitive?.contentOrNull
        if (type != "OTHER" || uid == null || !EMAID.matches(uid)) return json
        return JsonObject(
            json.toMutableMap().apply {
                put("type", JsonPrimitive("EMAID"))
                putIfAbsent("contract_id", JsonPrimitive(uid))
            },
        )
    }

    private fun inboundSession(json: JsonObject): JsonObject =
        mapFields(json, "cdr_token" to ::inboundToken, "total_cost" to ::inboundPrice)

    private fun inboundCdr(json: JsonObject): JsonObject = mapFields(
        json,
        "cdr_token" to ::inboundToken,
        *PRICE_FIELDS.map { it to ::inboundPrice }.toTypedArray(),
    )

    private fun inboundTariff(json: JsonObject, side: MutableMap<String, String>): JsonObject {
        val map = json.toMutableMap()
        for (limit in listOf("min_price", "max_price")) {
            val price = map[limit] as? JsonObject ?: continue
            if ("before_taxes" in price) continue
            map[limit] = buildJsonObject {
                price["excl_vat"]?.let { put("before_taxes", it) }
                price["incl_vat"]?.let { put("after_taxes", it) }
            }
        }
        if ("tax_included" !in map) {
            map["tax_included"] = JsonPrimitive(config.taxIncludedFallback)
            side["tax_included"] = "defaulted"
        }
        moveGireveId(map, "", side)
        return JsonObject(map)
    }

    private fun inboundLocation(json: JsonObject, side: MutableMap<String, String>): JsonObject {
        val map = json.toMutableMap()
        moveGireveId(map, "", side)
        val evses = (map["evses"] as? JsonArray)?.mapIndexed { i, evseElement ->
            val evse = evseElement.jsonObject.toMutableMap()
            evse.putIfAbsent("parking", JsonArray(emptyList()))
            evse.putIfAbsent("accepted_service_providers", JsonArray(emptyList()))
            moveGireveId(evse, "/evses/$i", side)
            val connectors = (evse["connectors"] as? JsonArray)?.mapIndexed { j, connector ->
                val c = connector.jsonObject.toMutableMap()
                moveGireveId(c, "/evses/$i/connectors/$j", side)
                JsonObject(c)
            }
            if (connectors != null) evse["connectors"] = JsonArray(connectors)
            JsonObject(evse)
        }
        if (evses != null) map["evses"] = JsonArray(evses)
        return JsonObject(map)
    }

    private fun inboundPrice(element: JsonElement): JsonElement {
        val price = element as? JsonObject ?: return element
        if ("excl_vat" !in price) return price
        val excl = price["excl_vat"]!!.decimal()
        val incl = price["incl_vat"]?.decimal()
        return buildJsonObject {
            put("before_taxes", JsonPrimitive(excl.toDouble()))
            put(
                "taxes",
                JsonArray(
                    if (incl == null) emptyList() else listOf(
                        buildJsonObject {
                            put("name", "VAT")
                            put("amount", JsonPrimitive(incl.subtract(excl).toDouble()))
                        },
                    ),
                ),
            )
        }
    }

    // ---- outbound (canonical 2.3.0 -> hub) ----

    private fun outboundToken(json: JsonObject): JsonObject {
        if (json["type"]?.jsonPrimitive?.contentOrNull != "EMAID") return json
        val emaid = json["contract_id"]?.jsonPrimitive?.contentOrNull ?: json["uid"]?.jsonPrimitive?.contentOrNull
            ?: refuse("TOKEN_INVALID", "EMAID token without uid or contract_id")
        return JsonObject(
            json.toMutableMap().apply {
                put("type", JsonPrimitive("OTHER"))
                put("uid", JsonPrimitive(emaid))
                put("contract_id", JsonPrimitive(emaid))
            },
        )
    }

    private fun outboundStartSession(json: JsonObject): JsonObject {
        if (json["evse_uid"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
            refuse("EVSE_UID_REQUIRED", "the hub requires evse_uid on START_SESSION")
        }
        json["token"]?.let { token ->
            return JsonObject(json + ("token" to outboundToken(token.jsonObject)))
        }
        return json
    }

    // ---- helpers ----

    private fun moveGireveId(map: MutableMap<String, JsonElement>, pointer: String, side: MutableMap<String, String>) {
        val id = map.remove("gireve_id") ?: return
        side["gireve_id:$pointer"] = id.text()
    }

    private fun mapFields(json: JsonObject, vararg transforms: Pair<String, (JsonObject) -> JsonElement>): JsonObject {
        val map = json.toMutableMap()
        for ((field, transform) in transforms) {
            val value = map[field] as? JsonObject ?: continue
            map[field] = transform(value)
        }
        return JsonObject(map)
    }

    private fun refuse(code: String, message: String): Nothing =
        throw HubException(hub, FailureClass.NON_RETRYABLE, code, message)

    private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.contentOrNull ?: ""

    private fun JsonElement?.stringValues(): List<String> =
        (this as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonElement.decimal(): BigDecimal = BigDecimal(jsonPrimitive.content)

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    companion object {
        private val PRICE_FIELDS = listOf(
            "total_cost", "total_fixed_cost", "total_energy_cost", "total_time_cost",
            "total_parking_cost", "total_reservation_cost",
        )
        private val BLOCKED_MODULES = setOf("payments", "chargingprofiles", "hubclientinfo")
        private val BLOCKED_COMMANDS = setOf("RESERVE_NOW", "CANCEL_RESERVATION", "UNLOCK_CONNECTOR")
        private val PLUG_AND_CHARGE_CAPABILITIES = setOf("ISO_15118_2_PLUG_AND_CHARGE", "ISO_15118_20_PLUG_AND_CHARGE")

        /** ISO 15118-1 Annex H eMAID, check digit optional. */
        val EMAID = Regex("^[A-Za-z]{2}-?[A-Za-z0-9]{3}-?C[A-Za-z0-9]{8}(-?[A-Za-z0-9])?$")

        /**
         * One entry per rule of the conversion table. IMPLEMENTED names the code that applies it; NO_TRANSFORM says why
         * the rule needs no JSON change. `ConversionTable.verifyCovered` compares these keys with the table.
         */
        val COVERAGE: Map<String, String> = mapOf(
            "VersionNumber" to "IMPLEMENTED wireVersion",
            "Credentials.hub_party_id" to "IMPLEMENTED inbound/outbound CREDENTIALS",
            "Role=HUB" to "IMPLEMENTED inboundCredentials moves the HUB role to side data",
            "Price" to "IMPLEMENTED inboundPrice",
            "Tariff.min_price/max_price" to "IMPLEMENTED inboundTariff",
            "Tariff.tax_included" to "IMPLEMENTED inboundTariff (fallback, see Ocpi221Config)",
            "Tariff.type" to "NO_TRANSFORM extra tariff types never arrive; only REGULAR is forwarded",
            "TariffDimensionType=SESSION_TIME" to "NO_TRANSFORM passed through as an extension value",
            "Location.help_phone" to "NO_TRANSFORM absent means null",
            "Location.address" to "NO_TRANSFORM canonical limit widens to 255 in ocpi-model",
            "Location.state" to "NO_TRANSFORM canonical limit widens to 45 in ocpi-model",
            "EVSE.parking" to "IMPLEMENTED inboundLocation",
            "EVSE.accepted_service_providers" to "IMPLEMENTED inboundLocation",
            "Connector.capabilities" to "IMPLEMENTED plugAndChargeCapable reads both levels",
            "ConnectorType=[MCS,SAE_J3400]" to "NO_TRANSFORM GIREVE encoding unknown; values pass through",
            "ParkingRestriction=[EMPLOYEES,TAXIS,TENANTS]" to "NO_TRANSFORM never sent by the hub",
            "Token.type=EMAID" to "IMPLEMENTED inboundToken/outboundToken",
            "Token#push" to "NO_TRANSFORM transport rule (PUT only, no PATCH, no pull); enforced by the GIREVE adapter",
            "CDR.SignedValue.plain_data" to "NO_TRANSFORM canonical limit widens to 5000 in ocpi-model",
            "CDR.{credit,credit_reference_id,home_charging_compensation}" to "NO_TRANSFORM the hub never sends them",
            "Location/EVSE/Connector/Tariff.gireve_id" to "IMPLEMENTED moveGireveId (side data)",
            "Session.id" to "NO_TRANSFORM hub id kept as is; naming rule documented in the GIREVE adapter",
            "CDR.id" to "NO_TRANSFORM CPO id kept as is",
            "module:payments" to "IMPLEMENTED checkModule",
            "module:chargingprofiles" to "IMPLEMENTED checkModule",
            "module:hubclientinfo" to "IMPLEMENTED checkModule",
            "Session#charging_preferences" to "IMPLEMENTED checkEndpoint",
            "Command=[RESERVE_NOW,CANCEL_RESERVATION,UNLOCK_CONNECTOR]" to "IMPLEMENTED checkCommand",
            "StartSession.evse_uid" to "IMPLEMENTED outboundStartSession",
        )
    }
}
