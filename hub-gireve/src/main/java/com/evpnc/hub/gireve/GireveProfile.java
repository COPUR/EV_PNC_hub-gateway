package com.evpnc.hub.gireve;

import com.evpnc.hub.core.ocpi221.OcpiParty;
import com.evpnc.hub.core.profile.ProfileDocument;
import com.evpnc.hub.core.profile.Profiles;
import com.evpnc.hub.core.resilience.FixedSchedule;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Typed view on {@code gireve-ocpi-2.2.1.profile.yaml}. */
public final class GireveProfile {
    public static final String FILE = "gireve-ocpi-2.2.1.profile.yaml";

    private final ProfileDocument document;
    private final String countryCode;
    private final List<Integer> uidByteLengths;
    private final boolean locationReferenceRequired;
    private final Set<String> unsupportedCommands;
    private final Set<String> supportedModules;
    private final Duration meterUpdateMinInterval;
    private final FixedSchedule retrySchedule;
    private final Set<Integer> waitStatuses;

    public GireveProfile(ProfileDocument document) {
        this.document = document;
        this.countryCode = document.string("hub.country_code");
        this.uidByteLengths = document.stringList("tokens.rfid_uid.bytes").stream().map(Integer::valueOf).toList();
        this.locationReferenceRequired = document.bool("tokens.authorize.location_reference_required");
        this.unsupportedCommands = Set.copyOf(document.stringList("commands.not_supported"));
        this.supportedModules = Set.copyOf(document.stringList("modules.supported"));
        this.meterUpdateMinInterval = Duration.ofSeconds(document.integer("sessions.meter_update_min_interval_seconds"));
        this.retrySchedule = new FixedSchedule(document.stringList("reliability.backoff_example_minutes").stream()
                .map(minutes -> Duration.ofMinutes(Long.parseLong(minutes))).collect(Collectors.toList()));
        this.waitStatuses = document.stringList("reliability.http_wait_codes").stream().map(Integer::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    public static GireveProfile load() {
        return new GireveProfile(Profiles.fromClasspath(FILE));
    }

    public ProfileDocument document() {
        return document;
    }

    public OcpiParty hubParty(GireveEnvironment environment) {
        return new OcpiParty(countryCode, document.string(
                environment == GireveEnvironment.PRODUCTION ? "hub.party_id.production" : "hub.party_id.pre_production"));
    }

    public List<Integer> uidByteLengths() {
        return uidByteLengths;
    }

    public boolean locationReferenceRequired() {
        return locationReferenceRequired;
    }

    public Set<String> unsupportedCommands() {
        return unsupportedCommands;
    }

    public Set<String> supportedModules() {
        return supportedModules;
    }

    public Duration meterUpdateMinInterval() {
        return meterUpdateMinInterval;
    }

    /** Store-and-forward retry for sessions and CDRs we send: 5, 10, 20 minutes in the guide's example. */
    public FixedSchedule retrySchedule() {
        return retrySchedule;
    }

    /** HTTP statuses that mean "wait and try again" (425, 429). */
    public Set<Integer> waitStatuses() {
        return waitStatuses;
    }
}
