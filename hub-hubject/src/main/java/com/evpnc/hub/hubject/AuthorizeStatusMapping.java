package com.evpnc.hub.hubject;

import com.evpnc.hub.core.AuthChannel;
import com.evpnc.hub.core.DecisionReason;
import com.evpnc.hub.core.profile.ProfileDocument;
import com.evpnc.hub.core.profile.Profiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The decision -> StatusCode table, loaded as configuration (T-HUBJECT-25). */
public final class AuthorizeStatusMapping {
    public static final String FILE = "hubject-authorize-statuscode.mapping.yaml";

    private final ProfileDocument document;
    private final OicpStatus allowed;
    private final OicpStatus policyTimeout;
    private final OicpStatus policyFailed;
    private final OicpStatus malformedRequest;

    public AuthorizeStatusMapping(ProfileDocument document) {
        this.document = document;
        // Fail at start-up if the table names a code that is not in the official list.
        List<String> codes = new ArrayList<>();
        codes.add(document.string("allowed"));
        document.map("by_reason").values().forEach(v -> {
            if (v instanceof Map<?, ?> byChannel) {
                byChannel.values().forEach(code -> codes.add(code.toString()));
            } else {
                codes.add(v.toString());
            }
        });
        document.map("fallback").values().forEach(v -> codes.add(v.toString()));
        List<String> unknown = codes.stream().filter(c -> OicpStatus.lookup(c) == null).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(document.name() + " uses codes outside the OICP list: " + unknown);
        }
        this.allowed = status(document.string("allowed"));
        this.policyTimeout = status(document.string("fallback.policy_timeout"));
        this.policyFailed = status(document.string("fallback.policy_failed"));
        this.malformedRequest = status(document.string("fallback.malformed_request"));
    }

    public static AuthorizeStatusMapping load() {
        return new AuthorizeStatusMapping(Profiles.fromClasspath(FILE));
    }

    public OicpStatus allowed() {
        return allowed;
    }

    public OicpStatus policyTimeout() {
        return policyTimeout;
    }

    public OicpStatus policyFailed() {
        return policyFailed;
    }

    public OicpStatus malformedRequest() {
        return malformedRequest;
    }

    public OicpStatus forReason(DecisionReason reason, AuthChannel channel) {
        Object entry = document.node("by_reason." + reason.name());
        if (entry == null) {
            return status(document.string("by_reason.OTHER"));
        }
        Object code = entry;
        if (entry instanceof Map<?, ?> byChannel) {
            Object perChannel = byChannel.get(channel.name());
            code = perChannel != null ? perChannel : document.string("by_reason.OTHER");
        }
        return status(code.toString());
    }

    private static OicpStatus status(String code) {
        OicpStatus status = OicpStatus.lookup(code);
        if (status == null) {
            throw new IllegalStateException("unknown OICP code " + code);
        }
        return status;
    }
}
