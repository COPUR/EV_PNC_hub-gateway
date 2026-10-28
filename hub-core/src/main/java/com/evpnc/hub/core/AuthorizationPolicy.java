package com.evpnc.hub.core;

/** Policy hook: price threshold, operator allow and block lists, consent. Implemented by the policy engine. */
@FunctionalInterface
public interface AuthorizationPolicy {
    AuthorizeDecision decide(AuthorizeRequest request);
}
