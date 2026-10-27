package com.evpnc.hub.gireve

/**
 * GIREVE wants RFID UIDs as uppercase hexadecimal of 4 or 7 bytes, zero-padded (RH-GIREVE-TOK-003). "Zero-padded" is read as
 * "leading zeros are kept"; a 4-byte UID is not widened to 7 bytes. That reading is not confirmed by GIREVE (see docs/refactoring-report.md).
 */
object RfidUid {
    private val HEX = Regex("^[0-9A-Fa-f]+$")

    fun normalise(raw: String, allowedBytes: List<Int>): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !HEX.matches(trimmed) || trimmed.length % 2 != 0) return null
        if (trimmed.length / 2 !in allowedBytes) return null
        return trimmed.uppercase()
    }
}
