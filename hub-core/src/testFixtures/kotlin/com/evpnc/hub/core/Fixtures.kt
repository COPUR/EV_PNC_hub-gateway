package com.evpnc.hub.core

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock the test moves by hand. */
class MutableClock(private var now: Instant = Instant.parse("2026-11-02T09:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

fun authorizeRequest(
    hub: Hub = Hub.HUBJECT,
    channel: AuthChannel = AuthChannel.RFID,
    token: TokenRef = TokenRef("04A2B3C4D5E6F7", TokenType.RFID),
    at: Instant = Instant.parse("2026-11-02T09:00:00Z"),
) = AuthorizeRequest(hub = hub, requestId = "req-1", channel = channel, token = token, receivedAt = at)

fun hubCdr(hub: Hub = Hub.HUBJECT, sessionId: String = "b2688855-7f00-0002-6d8e-48d883f6abb6") = HubCdr(
    hub = hub,
    hubSessionId = sessionId,
    evseId = "TR*ZZZ*E0001*1",
    token = TokenRef("04A2B3C4D5E6F7", TokenType.RFID),
    sessionStart = Instant.parse("2026-11-02T09:00:00Z"),
    sessionEnd = Instant.parse("2026-11-02T09:30:00Z"),
    energyKwh = 31.2,
    pricedBy = PricedBy.NOBODY,
)

/** Records requests and answers from a queue, so tests read as "the hub says X, we do Y". */
class FakeTransport : HubTransport {
    val sent = mutableListOf<HttpRequestSpec>()
    var next: () -> HttpResponseSpec = { error("no answer queued") }

    override fun send(request: HttpRequestSpec): HttpResponseSpec {
        sent += request
        return next()
    }

    fun answers(status: Int, body: String) {
        next = { HttpResponseSpec(status, body) }
    }
}

class RecordingSink : CdrSink {
    val received = mutableListOf<HubCdr>()
    var failing = false

    override fun accept(cdr: HubCdr) {
        if (failing) error("payments down")
        received += cdr
    }
}

