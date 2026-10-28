package com.evpnc.hub.core;

import java.util.Objects;

/** A value that must never reach a log line (QR PIN, tokens, client secrets). */
public final class Secret {
    private final String value;

    public Secret(String value) {
        this.value = Objects.requireNonNull(value);
    }

    public String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "***";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Secret s && s.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
