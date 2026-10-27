package com.evpnc.hub.gireve

import com.evpnc.hub.core.PricedBy

/** CDR remark prefixes (guide section 4.7): `[*]` priced by GIREVE, `[?]` price not significant, otherwise the CPO's price. */
object GireveCdrPricing {
    fun classify(remark: String?): PricedBy {
        val text = remark?.trimStart() ?: return PricedBy.CPO
        return when {
            text.startsWith("[*]") -> PricedBy.HUB
            text.startsWith("[?]") -> PricedBy.NOBODY
            else -> PricedBy.CPO
        }
    }
}
