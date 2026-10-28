package com.evpnc.hub.hubject;

import com.evpnc.hub.core.profile.ProfileDocument;
import com.evpnc.hub.core.profile.Profiles;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Typed view on {@code hubject-oicp-2.3-emp.profile.yaml}. The adapter reads rules from here, never from constants. */
public final class HubjectProfile {
    public static final String FILE = "hubject-oicp-2.3-emp.profile.yaml";

    private final ProfileDocument document;
    private final Duration authorizeResponseTimeout;
    private final Duration cpoRemoteStartWindow;
    private final Duration cpoRemoteStopWindow;
    private final Duration remoteClientTimeout;
    private final Map<String, Integer> rateLimitsPerMinute;

    public HubjectProfile(ProfileDocument document) {
        this.document = document;
        this.authorizeResponseTimeout = Duration.ofSeconds(document.integer("timeouts.authorize_start_response_s.value"));
        this.cpoRemoteStartWindow = Duration.ofSeconds(document.integer("timeouts.cpo_remote_start_window_s.value"));
        this.cpoRemoteStopWindow = Duration.ofSeconds(document.integer("timeouts.cpo_remote_stop_window_s.value"));
        Duration longer = cpoRemoteStartWindow.compareTo(cpoRemoteStopWindow) >= 0 ? cpoRemoteStartWindow : cpoRemoteStopWindow;
        this.remoteClientTimeout = longer.plusSeconds(5);
        Map<String, Integer> limits = new LinkedHashMap<>();
        document.map("rate_limits_per_minute.values").forEach((operation, limit) -> limits.put(operation, ((Number) limit).intValue()));
        this.rateLimitsPerMinute = Map.copyOf(limits);
    }

    public static HubjectProfile load() {
        return new HubjectProfile(Profiles.fromClasspath(FILE));
    }

    public ProfileDocument document() {
        return document;
    }

    /** How long Hubject waits for our authorize answer. */
    public Duration authorizeResponseTimeout() {
        return authorizeResponseTimeout;
    }

    public Duration cpoRemoteStartWindow() {
        return cpoRemoteStartWindow;
    }

    public Duration cpoRemoteStopWindow() {
        return cpoRemoteStopWindow;
    }

    /**
     * Our client timeout for remote start and stop must exceed the CPO window the HBS waits for (RH-HUBJECT-ERR-005: at
     * least 65 s with the 60 s window). The support page says 40 s, the guide 60 s; the larger one plus 5 s wins.
     */
    public Duration remoteClientTimeout() {
        return remoteClientTimeout;
    }

    public Map<String, Integer> rateLimitsPerMinute() {
        return rateLimitsPerMinute;
    }
}
