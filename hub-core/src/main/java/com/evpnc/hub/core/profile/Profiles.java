package com.evpnc.hub.core.profile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class Profiles {
    private Profiles() {}

    /**
     * Profiles are vendored from EV_PNC_hub-contracts under {@code profiles/} on the classpath.
     * {@code tools/sync-contracts.sh} keeps them equal.
     */
    public static ProfileDocument fromClasspath(String file) {
        try (InputStream stream = Profiles.class.getClassLoader().getResourceAsStream("profiles/" + file)) {
            if (stream == null) {
                throw new IllegalStateException("profile 'profiles/" + file + "' not on the classpath");
            }
            return ProfileDocument.parse(file, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
