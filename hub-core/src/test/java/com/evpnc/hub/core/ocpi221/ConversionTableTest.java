package com.evpnc.hub.core.ocpi221;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.profile.Profiles;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The guard against silent drift: the vendored conversion table and the compat code must list the same rules. */
class ConversionTableTest {
    private final ConversionTable table = ConversionTable.from(Profiles.fromClasspath("ocpi-2.3.0-to-2.2.1.conversion.yaml"));

    @Test
    void everyRuleInTheTableIsHandledOrExplicitlyMarkedAsNeedingNoTransform() {
        table.verifyCovered(Ocpi221Compat.COVERAGE.keySet());
        assertEquals(29, table.ruleKeys().size(), "the table changed size; review the new rules and update COVERAGE");
    }

    @Test
    void aRuleTheCodeDoesNotKnowMakesTheCheckFailWithTheRuleName() {
        List<String> keys = new ArrayList<>(table.ruleKeys());
        keys.add("Tariff.brand_new_field");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ConversionTable(keys).verifyCovered(Ocpi221Compat.COVERAGE.keySet()));
        assertTrue(error.getMessage().contains("Tariff.brand_new_field"), error.getMessage());
    }

    @Test
    void aRuleRemovedFromTheTableLeavesAStaleEntryThatTheCheckReports() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ConversionTable(table.ruleKeys().subList(1, table.ruleKeys().size()))
                        .verifyCovered(Ocpi221Compat.COVERAGE.keySet()));
        assertTrue(error.getMessage().contains("VersionNumber"), error.getMessage());
    }
}
