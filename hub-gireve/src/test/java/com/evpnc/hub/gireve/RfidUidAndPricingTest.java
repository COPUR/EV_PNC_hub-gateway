package com.evpnc.hub.gireve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.evpnc.hub.core.PricedBy;
import java.util.List;
import org.junit.jupiter.api.Test;

class RfidUidAndPricingTest {
    private final List<Integer> bytes = List.of(4, 7);

    @Test
    void uidsAreUppercasedAndLeadingZerosSurvive() {
        assertEquals("04A2B3C4D5E6F7", RfidUid.normalise("04a2b3c4d5e6f7", bytes));
        assertEquals("0A0B0C0D", RfidUid.normalise(" 0a0b0c0d ", bytes));
    }

    @Test
    void only4And7ByteHexUidsPass() {
        for (String uid : List.of("", "xyz", "04A2B3", "04A2B3C4D5", "04A2B3C4D5E6F7AA", "04A2B3C4D5E6F", "0G0B0C0D")) {
            assertNull(RfidUid.normalise(uid, bytes), "'" + uid + "'");
        }
    }

    @Test
    void a4ByteUidIsNotWidenedTo7BytesReadingOfZeroPaddedToBeConfirmedWithGireve() {
        assertEquals("0A0B0C0D", RfidUid.normalise("0A0B0C0D", bytes));
    }

    @Test
    void remarkPrefixesSayWhoPricedTheCdr() {
        assertEquals(PricedBy.HUB, GireveCdrPricing.classify("[*] priced by Gireve"));
        assertEquals(PricedBy.NOBODY, GireveCdrPricing.classify("  [?] price not significant"));
        assertEquals(PricedBy.CPO, GireveCdrPricing.classify("regular session"));
        assertEquals(PricedBy.CPO, GireveCdrPricing.classify(null));
        assertEquals(PricedBy.CPO, GireveCdrPricing.classify("text [*] later"));
    }
}
