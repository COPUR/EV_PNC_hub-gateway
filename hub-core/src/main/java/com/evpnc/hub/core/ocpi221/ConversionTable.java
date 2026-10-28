package com.evpnc.hub.core.ocpi221;

import com.evpnc.hub.core.profile.ProfileDocument;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rule list of {@code ocpi-2.3.0-to-2.2.1.conversion.yaml}. The compat layer does not interpret the rules as a
 * script; it implements each by hand (small, reviewable) and this class guarantees no rule is forgotten: when the table
 * gains or loses a rule, {@link #verifyCovered} fails until the code and {@link Ocpi221Compat#COVERAGE} follow.
 */
public final class ConversionTable {
    private final List<String> ruleKeys;

    public ConversionTable(List<String> ruleKeys) {
        this.ruleKeys = List.copyOf(ruleKeys);
    }

    public List<String> ruleKeys() {
        return ruleKeys;
    }

    public void verifyCovered(Set<String> coverage) {
        List<String> missing = ruleKeys.stream().filter(k -> !coverage.contains(k)).toList();
        List<String> stale = coverage.stream().filter(k -> !ruleKeys.contains(k)).sorted().toList();
        if (!missing.isEmpty() || !stale.isEmpty()) {
            throw new IllegalStateException(
                    "conversion table and compat layer disagree. In the table only: " + missing + ". In the code only: " + stale);
        }
    }

    public static ConversionTable from(ProfileDocument profile) {
        if (!(profile.node("rules") instanceof List<?> rules)) {
            throw new IllegalStateException("conversion table has no rules");
        }
        return new ConversionTable(rules.stream().map(rule -> key((Map<?, ?>) rule)).toList());
    }

    /** Stable name of a rule: subject, then field, value(s), listed fields, endpoint. */
    static String key(Map<?, ?> rule) {
        Object object = rule.get("object");
        String subject = object != null ? object.toString() : "module:" + rule.get("module");
        StringBuilder sb = new StringBuilder(subject);
        if (rule.get("field") != null) {
            sb.append('.').append(rule.get("field"));
        }
        if (rule.get("value") != null) {
            sb.append('=').append(rule.get("value"));
        }
        if (rule.get("values") instanceof List<?> values) {
            sb.append("=[").append(join(values)).append(']');
        }
        if (rule.get("fields") instanceof List<?> fields) {
            sb.append(".{").append(join(fields)).append('}');
        }
        if (rule.get("endpoint") != null) {
            sb.append('#').append(rule.get("endpoint"));
        }
        if (rule.containsKey("push")) {
            sb.append("#push");
        }
        return sb.toString();
    }

    private static String join(List<?> list) {
        return String.join(",", list.stream().map(String::valueOf).toList());
    }
}
