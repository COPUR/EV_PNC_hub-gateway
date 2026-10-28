package com.evpnc.hub.core.ocpi221;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OCPI request headers as a hub wants them: routing headers on every call except {@code credentials} and
 * {@code versions}, request and correlation ids echoed back on responses, and the token Base64-encoded in
 * {@code Authorization}.
 */
public final class OcpiHeaders {
    private static final Set<String> NO_ROUTING = Set.of("credentials", "versions");

    private OcpiHeaders() {}

    public static Map<String, String> request(
            String module, OcpiParty from, OcpiParty to, String token, String requestId, String correlationId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Token " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
        headers.put("X-Request-ID", requestId);
        headers.put("X-Correlation-ID", correlationId);
        if (!NO_ROUTING.contains(module)) {
            headers.put("OCPI-from-country-code", from.countryCode());
            headers.put("OCPI-from-party-id", from.partyId());
            headers.put("OCPI-to-country-code", to.countryCode());
            headers.put("OCPI-to-party-id", to.partyId());
        }
        return headers;
    }

    /** A response must carry the ids of its request unchanged. @return what is wrong, empty when fine. */
    public static List<String> checkEcho(Map<String, String> request, Map<String, String> response) {
        List<String> problems = new ArrayList<>();
        for (String name : List.of("X-Request-ID", "X-Correlation-ID")) {
            String sent = valueIgnoringCase(request, name);
            String got = valueIgnoringCase(response, name);
            if (sent != null && !sent.equals(got)) {
                problems.add(name + " not echoed (sent '" + sent + "', got '" + (got == null ? "nothing" : got) + "')");
            }
        }
        return problems;
    }

    private static String valueIgnoringCase(Map<String, String> map, String name) {
        return map.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
