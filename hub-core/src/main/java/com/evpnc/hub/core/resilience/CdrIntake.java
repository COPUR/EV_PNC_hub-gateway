package com.evpnc.hub.core.resilience;

import com.evpnc.hub.core.CdrSink;
import com.evpnc.hub.core.HubCdr;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * CDR intake, answer-first (RH-HUBJECT-ERR-006): hubs never resend a CDR that failed, so the adapter answers positively
 * as soon as the record is safe. Duplicates are dropped by (hub, session id). A failing sink parks the record for replay
 * instead of losing it.
 */
public final class CdrIntake {
    private final CdrSink sink;
    private final IdempotencyStore idempotency;
    private final ArrayDeque<HubCdr> parked = new ArrayDeque<>();

    public CdrIntake(CdrSink sink, IdempotencyStore idempotency) {
        this.sink = sink;
        this.idempotency = idempotency;
    }

    public IntakeResult receive(HubCdr cdr) {
        if (!idempotency.firstTime(key(cdr))) {
            return IntakeResult.DUPLICATE;
        }
        try {
            sink.accept(cdr);
            return IntakeResult.STORED;
        } catch (Exception e) {
            synchronized (parked) {
                parked.addLast(cdr);
            }
            return IntakeResult.PARKED;
        }
    }

    public int parkedCount() {
        synchronized (parked) {
            return parked.size();
        }
    }

    /** Retry parked records in arrival order. A record that fails again goes to the back. @return how many were delivered. */
    public int replay() {
        List<HubCdr> batch;
        synchronized (parked) {
            batch = new ArrayList<>(parked);
            parked.clear();
        }
        int delivered = 0;
        for (HubCdr cdr : batch) {
            try {
                sink.accept(cdr);
                delivered++;
            } catch (Exception e) {
                synchronized (parked) {
                    parked.addLast(cdr);
                }
            }
        }
        return delivered;
    }

    public static String key(HubCdr cdr) {
        return cdr.hub().key() + ":" + cdr.hubSessionId();
    }
}
