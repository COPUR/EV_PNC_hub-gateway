package com.evpnc.hub.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal

/** What the conversion table says cannot be sent to this hub at all (`action: block`). */
class Blocked(val what: String, val reason: String) : RuntimeException("$what is not available through this hub: $reason")

/** A message that cannot be converted as it stands (a required value is missing, or two fields disagree). */
class ConversionError(message: String) : RuntimeException(message)

enum class ObjectType { CREDENTIALS, TOKEN, START_SESSION, TARIFF, LOCATION, SESSION, CDR }

/** The converted object and the rules that fired, named as in the conversion table, so a test or a log can say why. */
data class Converted(val json: JsonObject, val applied: List<String>)

/** A place where the canonical OCPI 2.3.0 model has to give room for what 2.2.1 hubs send (conversion table: widen_canonical and friends). */
data class CanonicalNeed(val where: String, val need: String)

/**
 * The OCPI 2.3.0 (canonical) <-> 2.2.1 (as GIREVE speaks it) layer, T-GIREVE-02 and T-GIREVE-15. It works on the JSON
 * wire form, so it needs no generated model and no particular HTTP stack. Every rule is one entry of
 * `ocpi-2.3.0-to-2.2.1.conversion.yaml`; the entry's behaviour that depends on the hub (supported modules and commands,
 * the hub party id, whether `evse_uid` is required) is read from the [HubProfile].
 *
 * OUT is canonical to wire (the eMSP calls the IOP), IN is wire to canonical (the IOP calls the eMSP, or answers a pull).
 */
class Ocpi221Compat(private val profile: HubProfile, private val env: Environment = Environment.PRE_PRODUCTION) {

    // ---- OUT: what may be sent ------------------------------------------------------------------------------------

    fun versionToWire(canonical: String): String = if (canonical == "2.3.0") profile.baseVersion else canonical

    fun requireModule(module: String) {
        if (module in ALWAYS_BLOCKED_MODULES) throw Blocked("module $module", "added in 2.3.0 and unknown to a 2.2.1 hub")
        if (!profile.supportsModule(module)) throw Blocked("module $module", "the hub profile does not list it as supported")
    }

    fun requireCommand(command: String) {
        if (!profile.supportsCommand(command)) throw Blocked("command $command", "the hub supports ${profile.commandsSupported.sorted().joinToString(", ")}")
    }

    fun requireEndpoint(module: String, endpoint: String) {
        if (module == "sessions" && endpoint == "charging_preferences") throw Blocked("sessions/$endpoint", "charging preferences are not implemented by the hub")
        requireModule(module)
    }

    fun outbound(type: ObjectType, json: JsonObject): Converted {
        val applied = mutableListOf<String>()
        val out = when (type) {
            ObjectType.CREDENTIALS -> if ("hub_party_id" in json) json.without("hub_party_id").also { applied += "credentials.hub_party_id:drop" } else json
            ObjectType.TOKEN -> tokenOut(json, applied)
            ObjectType.START_SESSION -> {
                if (profile.startSessionEvseUidRequired && json["evse_uid"].isMissing()) {
                    throw ConversionError("START_SESSION needs evse_uid for this hub (it is optional in the canonical model)")
                }
                json
            }
            else -> throw IllegalArgumentException("no outbound conversion for $type; the eMSP does not send it to the IOP")
        }
        return Converted(out, applied)
    }

    private fun tokenOut(json: JsonObject, applied: MutableList<String>): JsonObject {
        if (json.str("type") != "EMAID") return json
        val emaid = json.str("contract_id") ?: throw ConversionError("EMAID token without contract_id")
        val uid = json.str("uid")
        if (uid != null && !uid.equals(emaid, ignoreCase = true)) {
            throw ConversionError("EMAID token has uid $uid but contract_id $emaid; the hub expects both to be the eMAID")
        }
        applied += "token.type:EMAID->OTHER"
        return JsonObject(json + mapOf("type" to JsonPrimitive("OTHER"), "uid" to JsonPrimitive(emaid)))
    }

    // ---- IN: what arrives ------------------------------------------------------------------------------------------

    fun inbound(type: ObjectType, json: JsonObject): Converted {
        val applied = mutableListOf<String>()
        val out = when (type) {
            ObjectType.CREDENTIALS -> credentialsIn(json, applied)
            ObjectType.TOKEN -> tokenIn(json, applied)
            ObjectType.TARIFF -> tariffIn(json, applied)
            ObjectType.LOCATION -> locationIn(json, applied)
            ObjectType.SESSION -> priced(json, listOf("total_cost"), applied).withNested("cdr_token") { tokenIn(it, applied) }
            ObjectType.CDR -> priced(json, CDR_PRICES, applied).withNested("cdr_token") { tokenIn(it, applied) }
            ObjectType.START_SESSION -> throw IllegalArgumentException("START_SESSION only goes out")
        }
        return Converted(out, applied)
    }

    private fun credentialsIn(json: JsonObject, applied: MutableList<String>): JsonObject {
        // A 2.2.1 hub has no hub_party_id; the roles may carry HUB, which the canonical Role enum lacks (accepted as is).
        if ("hub_party_id" in json) return json
        applied += "credentials.hub_party_id:default"
        return JsonObject(json + ("hub_party_id" to JsonPrimitive(profile.hubPartyId(env))))
    }

    private fun tokenIn(json: JsonObject, applied: MutableList<String>): JsonObject {
        val uid = json.str("uid") ?: return json
        if (json.str("type") != "OTHER" || !EMAID.matches(uid)) return json
        applied += "token.type:OTHER->EMAID"
        return JsonObject(json + ("type" to JsonPrimitive("EMAID")))
    }

    /** Price {excl_vat, incl_vat?} -> {before_taxes, taxes[]}; the VAT amount is the difference (an inference, not in the spec). */
    private fun priceIn(price: JsonObject, where: String, applied: MutableList<String>): JsonObject {
        if ("before_taxes" in price) return price
        val excl = price.number("excl_vat") ?: throw ConversionError("$where: price without excl_vat")
        val incl = price.number("incl_vat")
        applied += "price:excl_vat->before_taxes"
        val taxes = if (incl == null) JsonArray(emptyList()) else JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("VAT"), "amount" to literal(incl - excl)))))
        return JsonObject(mapOf("before_taxes" to literal(excl), "taxes" to taxes))
    }

    private fun priced(json: JsonObject, fields: List<String>, applied: MutableList<String>): JsonObject =
        fields.fold(json) { acc, f -> acc.withNested(f) { priceIn(it, f, applied) } }

    private fun tariffIn(json: JsonObject, applied: MutableList<String>): JsonObject {
        var out = json
        if ("tax_included" !in out) {
            applied += "tariff.tax_included:default"
            out = JsonObject(out + ("tax_included" to JsonPrimitive(TAX_UNKNOWN)))
        }
        for (field in listOf("min_price", "max_price")) {
            out = out.withNested(field) { limit ->
                if ("before_taxes" in limit) return@withNested limit
                applied += "tariff.$field:price->price_limit"
                val excl = limit.number("excl_vat") ?: throw ConversionError("$field without excl_vat")
                val incl = limit.number("incl_vat")
                JsonObject(buildMap { put("before_taxes", literal(excl)); if (incl != null) put("after_taxes", literal(incl)) })
            }
        }
        return externalRef(out, applied)
    }

    private fun locationIn(json: JsonObject, applied: MutableList<String>): JsonObject {
        var out = externalRef(json, applied)
        out = out.withArray("evses") { evse ->
            var e = externalRef(evse, applied)
            for (field in listOf("parking", "accepted_service_providers")) {
                if (field !in e) {
                    applied += "evse.$field:default"
                    e = JsonObject(e + (field to JsonArray(emptyList())))
                }
            }
            val evseLevel = (e["capabilities"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.filter { it in PNC_CAPABILITIES }
            e = e.withArray("connectors") { connector ->
                val c = externalRef(connector, applied)
                if (evseLevel.isEmpty()) return@withArray c
                val have = (c["capabilities"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                val missing = evseLevel.filter { it !in have }
                if (missing.isEmpty()) return@withArray c
                applied += "connector.capabilities:from-evse"
                JsonObject(c + ("capabilities" to JsonArray((have + missing).map { JsonPrimitive(it) })))
            }
            e
        }
        return out
    }

    /** `gireve_id` is a vendor extension; the canonical model keeps it as an external reference. */
    private fun externalRef(json: JsonObject, applied: MutableList<String>): JsonObject {
        val id = json.str("gireve_id") ?: return json
        applied += "gireve_id:external_ref"
        val ref = JsonObject(mapOf("system" to JsonPrimitive("GIREVE"), "id" to JsonPrimitive(id)))
        return JsonObject(json.without("gireve_id") + ("external_ref" to ref))
    }

    companion object {
        /** `UNKNOWN` is not in the canonical TaxIncluded enum (YES, NO, N/A); see [CANONICAL_NEEDS]. */
        const val TAX_UNKNOWN = "UNKNOWN"

        private val ALWAYS_BLOCKED_MODULES = setOf("payments")
        private val PNC_CAPABILITIES = setOf("ISO_15118_2_PLUG_AND_CHARGE", "ISO_15118_20_PLUG_AND_CHARGE")
        private val CDR_PRICES = listOf("total_cost", "total_fixed_cost", "total_energy_cost", "total_time_cost", "total_parking_cost", "total_reservation_cost")

        /** ISO 15118-2 eMAID: country (2), provider (3), `C`, instance (8), check digit (1), separators optional. */
        val EMAID = Regex("^[A-Z]{2}[*-]?[A-Z0-9]{3}[*-]?C[A-Z0-9]{8}[*-]?[0-9A-Z]$", RegexOption.IGNORE_CASE)

        /** Gaps the conversion creates in the canonical model, to be closed in EV_PNC_ocpi-model (not done here). */
        val CANONICAL_NEEDS = listOf(
            CanonicalNeed("Tariff.tax_included", "value UNKNOWN (2.2.1 tariffs have no such field)"),
            CanonicalNeed("Credentials.roles[].role", "value HUB (removed in 2.3.0, still sent by 2.2.1 hubs)"),
            CanonicalNeed("Location, EVSE, Connector, Tariff", "external_ref {system, id} for gireve_id"),
            CanonicalNeed("Location.address", "length 255 (2.3.0 has 45)"),
            CanonicalNeed("Location.state", "length 45 (2.3.0 has 20)"),
            CanonicalNeed("CDR.SignedValue.plain_data", "length 5000 (2.3.0 has 512)"),
        )

        @OptIn(ExperimentalSerializationApi::class)
        private fun literal(v: BigDecimal): JsonElement = JsonUnquotedLiteral(v.stripTrailingZeros().toPlainString())

        private fun JsonElement?.isMissing() = this == null || this is JsonNull || (this is JsonPrimitive && isString && content.isBlank())

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.number(key: String): BigDecimal? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBigDecimalOrNull()

        private fun JsonObject.without(key: String) = JsonObject(filterKeys { it != key })

        private fun JsonObject.withNested(key: String, f: (JsonObject) -> JsonObject): JsonObject {
            val inner = this[key] as? JsonObject ?: return this
            return JsonObject(this + (key to f(inner)))
        }

        private fun JsonObject.withArray(key: String, f: (JsonObject) -> JsonObject): JsonObject {
            val arr = this[key] as? JsonArray ?: return this
            return JsonObject(this + (key to JsonArray(arr.map { (it as? JsonObject)?.let(f) ?: it })))
        }

        private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
    }
}
