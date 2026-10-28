package com.evpnc.hub.core;

import java.time.Instant;

/** Builders for the objects most tests need, with the defaults the Kotlin fixtures had. */
public final class TestData {
    public static final Instant NOW = Instant.parse("2026-11-02T09:00:00Z");

    private TestData() {}

    public static AuthorizeRequest authorizeRequest() {
        return authorizeRequest(Hub.HUBJECT);
    }

    public static AuthorizeRequest authorizeRequest(Hub hub) {
        return authorizeRequest(hub, AuthChannel.RFID, new TokenRef("04A2B3C4D5E6F7", TokenType.RFID), NOW);
    }

    public static AuthorizeRequest authorizeRequest(Hub hub, AuthChannel channel, TokenRef token, Instant at) {
        return new AuthorizeRequest(hub, "req-1", channel, token, at);
    }

    public static HubCdr hubCdr() {
        return hubCdr(Hub.HUBJECT, "b2688855-7f00-0002-6d8e-48d883f6abb6");
    }

    public static HubCdr hubCdr(Hub hub, String sessionId) {
        return new HubCdr(
                hub,
                sessionId,
                "TR*ZZZ*E0001*1",
                new TokenRef("04A2B3C4D5E6F7", TokenType.RFID),
                Instant.parse("2026-11-02T09:00:00Z"),
                Instant.parse("2026-11-02T09:30:00Z"),
                31.2,
                PricedBy.NOBODY);
    }
}
