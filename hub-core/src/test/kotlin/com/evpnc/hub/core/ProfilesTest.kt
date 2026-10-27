package com.evpnc.hub.core

import com.evpnc.hub.core.profile.Confidence
import com.evpnc.hub.core.profile.ProfileDocument
import com.evpnc.hub.core.profile.Profiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfilesTest {
    private val doc = ProfileDocument.parse(
        "t",
        """
        timeouts:
          authorize: {value: 10, confidence: fact}
        items: [a, b]
        unsupported:
          - {item: x, confidence: "unknown (ask the hub)"}
        guess: {confidence: inference, nested: {value: 1}}
        """.trimIndent(),
    )

    @Test
    fun `values are read by dotted path, lists by index`() {
        assertEquals(10, doc.int("timeouts.authorize.value"))
        assertEquals(listOf("a", "b"), doc.stringList("items"))
        assertEquals("b", doc.string("items.1"))
    }

    @Test
    fun `confidence is the nearest tag on the node or an ancestor`() {
        assertEquals(Confidence.FACT, doc.confidence("timeouts.authorize"))
        assertEquals(Confidence.INFERENCE, doc.confidence("guess.nested.value"))
        assertEquals(null, doc.confidence("items"))
    }

    @Test
    fun `unknown entries are listed with their path`() {
        assertEquals(listOf("unsupported.0"), doc.unknownPaths())
    }

    @Test
    fun `a missing value fails loudly`() {
        assertFailsWith<IllegalStateException> { doc.int("timeouts.nope.value") }
    }

    @Test
    fun `a profile that is not a mapping or not on the classpath fails loudly`() {
        assertFailsWith<IllegalArgumentException> { ProfileDocument.parse("list", "- a\n- b") }
        assertFailsWith<IllegalStateException> { Profiles.fromClasspath("no-such.profile.yaml") }
        assertTrue(Profiles.fromClasspath("ocpi-2.3.0-to-2.2.1.conversion.yaml").has("rules"))
    }
}
