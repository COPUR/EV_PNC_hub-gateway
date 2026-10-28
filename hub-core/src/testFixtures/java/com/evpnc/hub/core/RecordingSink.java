package com.evpnc.hub.core;

import java.util.ArrayList;
import java.util.List;

public final class RecordingSink implements CdrSink {
    public final List<HubCdr> received = new ArrayList<>();
    public boolean failing = false;

    @Override
    public void accept(HubCdr cdr) {
        if (failing) {
            throw new IllegalStateException("payments down");
        }
        received.add(cdr);
    }
}
