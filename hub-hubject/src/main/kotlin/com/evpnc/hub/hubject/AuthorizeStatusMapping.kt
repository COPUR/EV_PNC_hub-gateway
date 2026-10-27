package com.evpnc.hub.hubject

import com.evpnc.hub.core.AuthChannel
import com.evpnc.hub.core.DecisionReason
import com.evpnc.hub.core.profile.ProfileDocument
import com.evpnc.hub.core.profile.Profiles

/** The decision -> StatusCode table, loaded as configuration (T-HUBJECT-25). */
class AuthorizeStatusMapping(private val document: ProfileDocument) {
    init {
        // Fail at start-up if the table names a code that is not in the official list.
        val codes = buildList {
            add(document.string("allowed"))
            document.map("by_reason").values.forEach { v ->
                if (v is Map<*, *>) v.values.forEach { add(it.toString()) } else add(v.toString())
            }
            document.map("fallback").values.forEach { add(it.toString()) }
        }
        val unknown = codes.filter { OicpStatus.lookup(it) == null }
        require(unknown.isEmpty()) { "${document.name} uses codes outside the OICP list: $unknown" }
    }

    val allowed: OicpStatus = status(document.string("allowed"))
    val policyTimeout: OicpStatus = status(document.string("fallback.policy_timeout"))
    val policyFailed: OicpStatus = status(document.string("fallback.policy_failed"))
    val malformedRequest: OicpStatus = status(document.string("fallback.malformed_request"))

    fun forReason(reason: DecisionReason, channel: AuthChannel): OicpStatus {
        val entry = document.node("by_reason.${reason.name}") ?: return status(document.string("by_reason.OTHER"))
        val code = if (entry is Map<*, *>) (entry[channel.name] ?: document.string("by_reason.OTHER")) else entry
        return status(code.toString())
    }

    private fun status(code: String) = OicpStatus.lookup(code) ?: error("unknown OICP code $code")

    companion object {
        const val FILE = "hubject-authorize-statuscode.mapping.yaml"

        fun load() = AuthorizeStatusMapping(Profiles.fromClasspath(FILE))
    }
}
