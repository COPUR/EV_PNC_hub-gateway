package com.evpnc.hub.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.profile.Confidence;
import com.evpnc.hub.core.profile.ProfileDocument;
import com.evpnc.hub.core.profile.Profiles;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfilesTest {
    private final ProfileDocument doc = ProfileDocument.parse(
            "t",
            """
            timeouts:
              authorize: {value: 10, confidence: fact}
            items: [a, b]
            unsupported:
              - {item: x, confidence: "unknown (ask the hub)"}
            guess: {confidence: inference, nested: {value: 1}}
            """);

    @Test
    void valuesAreReadByDottedPathListsByIndex() {
        assertEquals(10, doc.integer("timeouts.authorize.value"));
        assertEquals(List.of("a", "b"), doc.stringList("items"));
        assertEquals("b", doc.string("items.1"));
    }

    @Test
    void confidenceIsTheNearestTagOnTheNodeOrAnAncestor() {
        assertEquals(Confidence.FACT, doc.confidence("timeouts.authorize"));
        assertEquals(Confidence.INFERENCE, doc.confidence("guess.nested.value"));
        assertNull(doc.confidence("items"));
    }

    @Test
    void unknownEntriesAreListedWithTheirPath() {
        assertEquals(List.of("unsupported.0"), doc.unknownPaths());
    }

    @Test
    void aMissingValueFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> doc.integer("timeouts.nope.value"));
    }

    @Test
    void aProfileThatIsNotAMappingOrNotOnTheClasspathFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> ProfileDocument.parse("list", "- a\n- b"));
        assertThrows(IllegalStateException.class, () -> Profiles.fromClasspath("no-such.profile.yaml"));
        assertTrue(Profiles.fromClasspath("ocpi-2.3.0-to-2.2.1.conversion.yaml").has("rules"));
    }
}
