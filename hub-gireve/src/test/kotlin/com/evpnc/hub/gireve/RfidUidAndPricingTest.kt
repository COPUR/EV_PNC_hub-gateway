package com.evpnc.hub.gireve

import com.evpnc.hub.core.PricedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RfidUidAndPricingTest {
    private val bytes = listOf(4, 7)

    @Test
    fun `UIDs are uppercased and leading zeros survive`() {
        assertEquals("04A2B3C4D5E6F7", RfidUid.normalise("04a2b3c4d5e6f7", bytes))
        assertEquals("0A0B0C0D", RfidUid.normalise(" 0a0b0c0d ", bytes))
    }

    @Test
    fun `only 4 and 7 byte hex UIDs pass`() {
        listOf("", "xyz", "04A2B3", "04A2B3C4D5", "04A2B3C4D5E6F7AA", "04A2B3C4D5E6F", "0G0B0C0D").forEach {
            assertNull(RfidUid.normalise(it, bytes), "'$it'")
        }
    }

    @Test
    fun `a 4 byte UID is not widened to 7 bytes (reading of zero-padded to be confirmed with GIREVE)`() {
        assertEquals("0A0B0C0D", RfidUid.normalise("0A0B0C0D", bytes))
    }

    @Test
    fun `remark prefixes say who priced the CDR`() {
        assertEquals(PricedBy.HUB, GireveCdrPricing.classify("[*] priced by Gireve"))
        assertEquals(PricedBy.NOBODY, GireveCdrPricing.classify("  [?] price not significant"))
        assertEquals(PricedBy.CPO, GireveCdrPricing.classify("regular session"))
        assertEquals(PricedBy.CPO, GireveCdrPricing.classify(null))
        assertEquals(PricedBy.CPO, GireveCdrPricing.classify("text [*] later"))
    }
}
