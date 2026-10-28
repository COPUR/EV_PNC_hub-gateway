package com.evpnc.hub.gireve;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * GIREVE wants RFID UIDs as uppercase hexadecimal of 4 or 7 bytes, zero-padded (RH-GIREVE-TOK-003). "Zero-padded" is
 * read as "leading zeros are kept"; a 4-byte UID is not widened to 7 bytes. That reading is not confirmed by GIREVE (see
 * docs/refactoring-report.md).
 */
public final class RfidUid {
    private static final Pattern HEX = Pattern.compile("^[0-9A-Fa-f]+$");

    private RfidUid() {}

    /** @return the normalised UID, or null when it is not a valid GIREVE UID */
    public static String normalise(String raw, List<Integer> allowedBytes) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || !HEX.matcher(trimmed).matches() || trimmed.length() % 2 != 0) {
            return null;
        }
        if (!allowedBytes.contains(trimmed.length() / 2)) {
            return null;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }
}
