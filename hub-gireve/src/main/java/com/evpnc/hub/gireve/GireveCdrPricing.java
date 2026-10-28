package com.evpnc.hub.gireve;

import com.evpnc.hub.core.PricedBy;

/** CDR remark prefixes (guide section 4.7): {@code [*]} priced by GIREVE, {@code [?]} price not significant, otherwise the CPO's price. */
public final class GireveCdrPricing {
    private GireveCdrPricing() {}

    public static PricedBy classify(String remark) {
        if (remark == null) {
            return PricedBy.CPO;
        }
        String text = remark.stripLeading();
        if (text.startsWith("[*]")) {
            return PricedBy.HUB;
        }
        if (text.startsWith("[?]")) {
            return PricedBy.NOBODY;
        }
        return PricedBy.CPO;
    }
}
