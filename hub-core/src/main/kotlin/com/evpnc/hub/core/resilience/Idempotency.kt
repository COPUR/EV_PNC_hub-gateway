package com.evpnc.hub.core.resilience

import com.evpnc.hub.core.CdrSink
import com.evpnc.hub.core.HubCdr
import java.util.concurrent.ConcurrentHashMap

fun interface IdempotencyStore {
    /** @return true when [key] was not seen before. */
    fun firstTime(key: String): Boolean
}

class InMemoryIdempotencyStore : IdempotencyStore {
    private val seen = ConcurrentHashMap.newKeySet<String>()

    override fun firstTime(key: String): Boolean = seen.add(key)
}

enum class IntakeResult {
    /** Handed to the sink. */
    STORED,

    /** Seen before (the hub resent, or reconciliation found it again). Nothing was done. */
    DUPLICATE,

    /** The sink failed; the record is parked and will be replayed. The hub still gets a positive answer. */
    PARKED,
}

/**
 * CDR intake, answer-first (RH-HUBJECT-ERR-006): hubs never resend a CDR that failed, so the adapter answers positively
 * as soon as the record is safe. Duplicates are dropped by (hub, session id). A failing sink parks the record for replay
 * instead of losing it.
 */
class CdrIntake(
    private val sink: CdrSink,
    private val idempotency: IdempotencyStore,
) {
    private val parked = ArrayDeque<HubCdr>()

    fun receive(cdr: HubCdr): IntakeResult {
        if (!idempotency.firstTime(key(cdr))) return IntakeResult.DUPLICATE
        return try {
            sink.accept(cdr)
            IntakeResult.STORED
        } catch (_: Exception) {
            synchronized(parked) { parked.addLast(cdr) }
            IntakeResult.PARKED
        }
    }

    fun parkedCount(): Int = synchronized(parked) { parked.size }

    /** Retry parked records in arrival order. A record that fails again goes to the back. @return how many were delivered. */
    fun replay(): Int {
        val batch = synchronized(parked) { parked.toList().also { parked.clear() } }
        var delivered = 0
        for (cdr in batch) {
            try {
                sink.accept(cdr)
                delivered++
            } catch (_: Exception) {
                synchronized(parked) { parked.addLast(cdr) }
            }
        }
        return delivered
    }

    companion object {
        fun key(cdr: HubCdr): String = "${cdr.hub.key}:${cdr.hubSessionId}"
    }
}
