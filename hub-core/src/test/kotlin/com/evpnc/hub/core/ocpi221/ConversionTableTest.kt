package com.evpnc.hub.core.ocpi221

import com.evpnc.hub.core.profile.Profiles
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

/** The guard against silent drift: the vendored conversion table and the compat code must list the same rules. */
class ConversionTableTest {
    private val table = ConversionTable.from(Profiles.fromClasspath("ocpi-2.3.0-to-2.2.1.conversion.yaml"))

    @Test
    fun `every rule in the table is handled or explicitly marked as needing no transform`() {
        table.verifyCovered(Ocpi221Compat.COVERAGE.keys)
        assertEquals(29, table.ruleKeys.size, "the table changed size; review the new rules and update COVERAGE")
    }

    @Test
    fun `a rule the code does not know makes the check fail with the rule name`() {
        val error = assertFailsWith<IllegalStateException> {
            ConversionTable(table.ruleKeys + "Tariff.brand_new_field").verifyCovered(Ocpi221Compat.COVERAGE.keys)
        }
        assert("Tariff.brand_new_field" in error.message!!) { error.message!! }
    }

    @Test
    fun `a rule removed from the table leaves a stale entry that the check reports`() {
        val error = assertFailsWith<IllegalStateException> {
            ConversionTable(table.ruleKeys.drop(1)).verifyCovered(Ocpi221Compat.COVERAGE.keys)
        }
        assert("VersionNumber" in error.message!!) { error.message!! }
    }
}
