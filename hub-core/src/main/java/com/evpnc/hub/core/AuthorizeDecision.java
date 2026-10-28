package com.evpnc.hub.core;

import java.util.List;

public sealed interface AuthorizeDecision {

    /**
     * @param stopTokens tokens allowed to stop the session besides the starting one
     * @param authorizationReference reference the hub should repeat in the session and CDR (OCPI
     *     `authorization_reference`), or null
     */
    record Allowed(List<TokenRef> stopTokens, String authorizationReference) implements AuthorizeDecision {
        public Allowed {
            stopTokens = List.copyOf(stopTokens);
        }

        public Allowed() {
            this(List.of(), null);
        }

        public Allowed(List<TokenRef> stopTokens) {
            this(stopTokens, null);
        }
    }

    /** @param detail free text for logs, may be null */
    record Denied(DecisionOutcome outcome, List<DecisionReason> reasons, String detail) implements AuthorizeDecision {
        public Denied {
            if (outcome == DecisionOutcome.ALLOWED) {
                throw new IllegalArgumentException("Denied cannot carry ALLOWED");
            }
            reasons = List.copyOf(reasons);
        }

        public Denied(DecisionOutcome outcome, List<DecisionReason> reasons) {
            this(outcome, reasons, null);
        }
    }
}
