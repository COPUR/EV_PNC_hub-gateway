package com.evpnc.hub.core

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Path
import java.time.Duration
import java.time.Period
import java.time.ZonedDateTime
import kotlin.io.path.readText

class ProfileError(message: String) : RuntimeException(message)

enum class Environment { PRE_PRODUCTION, PRODUCTION }

enum class TokenEncoding { BASE64, PLAIN }

/** Who priced a CDR, from the first characters of its `remark` (GIREVE guide, CDR section). */
enum class PricedBy { HUB, NOT_SIGNIFICANT, CPO }

data class RfidUidRule(val uppercaseHex: Boolean, val bytes: Set<Int>, val preferredBytes: Int?)

/**
 * A hub profile as typed configuration (T-GIREVE-01): the part of `gireve-ocpi-2.2.1.profile.yaml` that decides what
 * the gateway does. Nothing here is GIREVE-specific code; another OCPI hub (ECHX later) is another YAML file.
 * A missing or malformed key fails the load with its path, so a bad profile never half-works.
 */
data class HubProfile(
    val name: String,
    val baseVersion: String,
    val hubCountryCode: String,
    val hubPartyIds: Map<Environment, String>,
    val tokenEncoding: TokenEncoding,
    val routingHeaders: List<String>,
    val routingNotRequiredOn: Set<String>,
    val tracingHeaders: List<String>,
    val registrationImplemented: Set<String>,
    val modulesSupported: Set<String>,
    val modulesNotSupported: Set<String>,
    val rfidUid: RfidUidRule,
    val commandsSupported: Set<String>,
    val commandsNotSupported: Set<String>,
    val startSessionEvseUidRequired: Boolean,
    val pullMaxPerPage: Int,
    val pullMaxRange: Period,
    val cdrRemarkPrefixes: Map<String, PricedBy>,
    val tariffTypesForwarded: Set<String>,
    val confidence: String,
) {
    // ---- behaviour the profile decides ----------------------------------------------------------------------------

    fun supportsModule(module: String) = module in modulesSupported

    fun supportsCommand(command: String) = command in commandsSupported

    /** `FR107` or `FR007`: the value of `hub_party_id` for credentials that do not carry one (2.2.1 has none). */
    fun hubPartyId(env: Environment): String = hubCountryCode + hubPartyIds.getValue(env)

    /** Routing headers are required on every module call except the ones the profile lists. */
    fun requiredHeaders(module: String): List<String> =
        (if (module in routingNotRequiredOn) emptyList() else routingHeaders) + tracingHeaders

    /** The `Authorization` header value for a credentials token. */
    fun authorizationHeader(token: String): String =
        "Token " + if (tokenEncoding == TokenEncoding.BASE64) java.util.Base64.getEncoder().encodeToString(token.toByteArray()) else token

    /** Page size to ask for: never above what the hub allows. */
    fun pageLimit(requested: Int?): Int = minOf(requested ?: pullMaxPerPage, pullMaxPerPage).coerceAtLeast(1)

    /** Whether a pull of [from]..[to] is within the hub's date-range limit (a calendar month for GIREVE tariffs). */
    fun withinPullRange(from: ZonedDateTime, to: ZonedDateTime): Boolean = !from.plus(pullMaxRange).isBefore(to)

    /**
     * Window length a planner should use to split a long history: the shortest calendar month (28 days), so a
     * window is accepted whatever month it starts in. The GIREVE mock is lenient (31 days); the gateway should not be.
     */
    fun safePullWindow(): Duration = Duration.ofDays(maxOf(1L, pullMaxRange.toTotalMonths() * 28 + pullMaxRange.days))

    /** Violations of the RFID uid rule, empty when the uid may be pushed. */
    fun rfidUidProblems(uid: String): List<String> {
        val out = mutableListOf<String>()
        if (!Regex("^[0-9A-Fa-f]+$").matches(uid)) return listOf("uid is not hexadecimal")
        if (rfidUid.uppercaseHex && uid != uid.uppercase()) out += "uid must be upper-case hex"
        if (uid.length % 2 != 0 || uid.length / 2 !in rfidUid.bytes) out += "uid must be ${rfidUid.bytes.sorted().joinToString(" or ")} bytes"
        return out
    }

    fun pricedBy(remark: String?): PricedBy =
        cdrRemarkPrefixes.entries.firstOrNull { remark?.startsWith(it.key) == true }?.value ?: PricedBy.CPO

    companion object {
        fun load(path: Path): HubProfile = parse(path.readText(), path.toString())

        /** A profile from the pinned hub-contracts checkout (`EV_PNC_CONTRACTS_DIR`, default `.contracts`). */
        fun fromContracts(file: String, contractsDir: Path = defaultContractsDir()): HubProfile =
            load(contractsDir.resolve("profiles").resolve(file))

        fun defaultContractsDir(): Path = Path.of(System.getenv("EV_PNC_CONTRACTS_DIR") ?: ".contracts")

        fun parse(yaml: String, origin: String = "profile"): HubProfile {
            val root = try {
                Load(LoadSettings.builder().build()).loadFromString(yaml)
            } catch (e: Exception) {
                throw ProfileError("$origin: not valid YAML: ${e.message}")
            }
            return Reader(root as? Map<*, *> ?: throw ProfileError("$origin: top level must be a mapping"), origin).profile()
        }
    }
}

private class Reader(private val root: Map<*, *>, private val origin: String) {
    private fun fail(path: String, why: String): Nothing = throw ProfileError("$origin: $path $why")

    private fun node(path: String): Any? = path.split('.').fold(root as Any?) { cur, key -> (cur as? Map<*, *>)?.get(key) }

    private fun map(path: String): Map<*, *> = node(path) as? Map<*, *> ?: fail(path, "must be a mapping")

    private fun string(path: String): String = node(path)?.toString()?.takeIf { it.isNotBlank() } ?: fail(path, "is missing")

    private fun strings(path: String, optional: Boolean = false): List<String> =
        when (val n = node(path)) {
            null -> if (optional) emptyList() else fail(path, "is missing")
            is List<*> -> n.map { it.toString() }
            else -> fail(path, "must be a list")
        }

    private fun int(path: String): Int = (node(path) as? Number)?.toInt() ?: fail(path, "must be a number")

    fun profile(): HubProfile {
        val rfid = map("tokens.rfid_uid")
        val bytes = (rfid["bytes"] as? List<*>)?.map { (it as? Number)?.toInt() ?: fail("tokens.rfid_uid.bytes", "must be numbers") }
            ?: fail("tokens.rfid_uid.bytes", "must be a list")
        val encoding = when (val e = string("transport.authorization_token_encoding")) {
            "base64" -> TokenEncoding.BASE64
            "plain" -> TokenEncoding.PLAIN
            else -> fail("transport.authorization_token_encoding", "must be base64 or plain, not $e")
        }
        val prefixes = map("cdrs.remark_prefix").entries.mapNotNull { (k, v) ->
            when (v.toString()) {
                "gireve_priced" -> k.toString() to PricedBy.HUB
                "price_not_significant" -> k.toString() to PricedBy.NOT_SIGNIFICANT
                else -> null // `none: cpo_priced` is the default, not a prefix
            }
        }.toMap()
        return HubProfile(
            name = string("profile"),
            baseVersion = string("base.version"),
            hubCountryCode = string("hub.country_code"),
            hubPartyIds = mapOf(
                Environment.PRE_PRODUCTION to string("hub.party_id.pre_production"),
                Environment.PRODUCTION to string("hub.party_id.production"),
            ),
            tokenEncoding = encoding,
            routingHeaders = strings("transport.headers.routing_required"),
            routingNotRequiredOn = strings("transport.headers.routing_not_required_on").toSet(),
            tracingHeaders = strings("transport.headers.tracing_required"),
            registrationImplemented = strings("registration.implemented").toSet(),
            modulesSupported = strings("modules.supported").toSet(),
            modulesNotSupported = strings("modules.not_supported").toSet(),
            rfidUid = RfidUidRule(string("tokens.rfid_uid.format") == "uppercase_hex", bytes.toSet(), (rfid["preferred"] as? Number)?.toInt()),
            commandsSupported = strings("commands.supported_cpo_side").toSet(),
            commandsNotSupported = strings("commands.not_supported").toSet(),
            startSessionEvseUidRequired = string("commands.start_session.evse_uid") == "required",
            pullMaxPerPage = int("tariffs.pull_limits.max_per_page"),
            pullMaxRange = period(string("tariffs.pull_limits.max_date_range")),
            cdrRemarkPrefixes = prefixes,
            tariffTypesForwarded = strings("tariffs.forwarded_to_emsp").toSet(),
            confidence = string("confidence"),
        )
    }

    private fun period(text: String): Period {
        val m = Regex("^\\s*(\\d+)\\s*(day|week|month)s?\\s*$").matchEntire(text) ?: fail("tariffs.pull_limits.max_date_range", "cannot be read as a duration: $text")
        val n = m.groupValues[1].toInt()
        return when (m.groupValues[2]) {
            "day" -> Period.ofDays(n)
            "week" -> Period.ofWeeks(n)
            else -> Period.ofMonths(n)
        }
    }
}
