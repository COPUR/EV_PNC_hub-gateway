package com.evpnc.hub.core.ocpi221

import com.evpnc.hub.core.profile.ProfileDocument

/**
 * The rule list of `ocpi-2.3.0-to-2.2.1.conversion.yaml`. The compat layer does not interpret the rules as a script;
 * it implements each by hand (small, reviewable) and this class guarantees no rule is forgotten:
 * when the table gains or loses a rule, [verifyCovered] fails until the code and [Ocpi221Compat.COVERAGE] follow.
 */
class ConversionTable(val ruleKeys: List<String>) {
    fun verifyCovered(coverage: Set<String>) {
        val missing = ruleKeys.filterNot { it in coverage }
        val stale = coverage.filterNot { it in ruleKeys }
        check(missing.isEmpty() && stale.isEmpty()) {
            "conversion table and compat layer disagree. In the table only: $missing. In the code only: $stale"
        }
    }

    companion object {
        fun from(profile: ProfileDocument): ConversionTable {
            val rules = profile.node("rules") as? List<*> ?: error("conversion table has no rules")
            return ConversionTable(rules.map { key(it as Map<*, *>) })
        }

        /** Stable name of a rule: subject, then field, value(s), listed fields, endpoint. */
        internal fun key(rule: Map<*, *>): String {
            val subject = rule["object"]?.toString() ?: "module:${rule["module"]}"
            return buildString {
                append(subject)
                rule["field"]?.let { append('.').append(it) }
                rule["value"]?.let { append('=').append(it) }
                (rule["values"] as? List<*>)?.let { append("=[").append(it.joinToString(",")).append(']') }
                (rule["fields"] as? List<*>)?.let { append(".{").append(it.joinToString(",")).append('}') }
                rule["endpoint"]?.let { append('#').append(it) }
                if (rule.containsKey("push")) append("#push")
            }
        }
    }
}
