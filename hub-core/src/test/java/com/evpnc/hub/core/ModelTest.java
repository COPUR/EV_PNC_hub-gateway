package com.evpnc.hub.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModelTest {
    @Test
    void aSecretNeverPrintsItsValue() {
        AuthorizeRequest request = TestData.authorizeRequest().withPin(new Secret("1234"));
        assertFalse(request.toString().contains("1234"), request.toString());
        assertEquals("1234", request.pin().reveal());
    }

    @Test
    void aDenialCannotCarryTheAllowedOutcome() {
        assertThrows(IllegalArgumentException.class, () -> new AuthorizeDecision.Denied(DecisionOutcome.ALLOWED, List.of()));
    }
}
