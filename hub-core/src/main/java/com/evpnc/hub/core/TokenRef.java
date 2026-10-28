package com.evpnc.hub.core;

import java.util.Objects;

/** @param contractId EvcoID / eMAID when the token carries one, else null */
public record TokenRef(String uid, TokenType type, String contractId) {
    public TokenRef {
        Objects.requireNonNull(uid);
        Objects.requireNonNull(type);
    }

    public TokenRef(String uid, TokenType type) {
        this(uid, type, null);
    }
}
