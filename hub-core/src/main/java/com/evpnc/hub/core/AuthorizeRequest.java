package com.evpnc.hub.core;

import java.time.Instant;
import java.util.Objects;

/**
 * @param requestId our id for this request; the hub's session id when the hub assigns one
 * @param evseId optional, null when absent
 * @param operatorId optional, null when absent
 * @param hubSessionId optional, null when absent
 * @param pin optional, null when absent
 */
public record AuthorizeRequest(
        Hub hub,
        String requestId,
        AuthChannel channel,
        TokenRef token,
        Instant receivedAt,
        String evseId,
        String operatorId,
        String hubSessionId,
        Secret pin) {

    public AuthorizeRequest {
        Objects.requireNonNull(hub);
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(channel);
        Objects.requireNonNull(token);
        Objects.requireNonNull(receivedAt);
    }

    public AuthorizeRequest(Hub hub, String requestId, AuthChannel channel, TokenRef token, Instant receivedAt) {
        this(hub, requestId, channel, token, receivedAt, null, null, null, null);
    }

    public AuthorizeRequest withEvseId(String value) {
        return new AuthorizeRequest(hub, requestId, channel, token, receivedAt, value, operatorId, hubSessionId, pin);
    }

    public AuthorizeRequest withOperatorId(String value) {
        return new AuthorizeRequest(hub, requestId, channel, token, receivedAt, evseId, value, hubSessionId, pin);
    }

    public AuthorizeRequest withHubSessionId(String value) {
        return new AuthorizeRequest(hub, requestId, channel, token, receivedAt, evseId, operatorId, value, pin);
    }

    public AuthorizeRequest withPin(Secret value) {
        return new AuthorizeRequest(hub, requestId, channel, token, receivedAt, evseId, operatorId, hubSessionId, value);
    }
}
