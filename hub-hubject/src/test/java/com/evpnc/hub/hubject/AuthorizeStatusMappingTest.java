package com.evpnc.hub.hubject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evpnc.hub.core.AuthChannel;
import com.evpnc.hub.core.DecisionReason;
import com.evpnc.hub.core.profile.ProfileDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthorizeStatusMappingTest {
    private final AuthorizeStatusMapping mapping = AuthorizeStatusMapping.load();

    @Test
    void anUnknownRfidIs102AndAnUnknownQrCodeIs101PerChannel() {
        assertEquals(OicpStatus.RFID_INVALID_UID, mapping.forReason(DecisionReason.TOKEN_UNKNOWN, AuthChannel.RFID));
        assertEquals(OicpStatus.QR_INVALID_CREDENTIALS, mapping.forReason(DecisionReason.TOKEN_UNKNOWN, AuthChannel.QR_CODE));
        assertEquals(OicpStatus.PLC_INVALID_EVCO_ID, mapping.forReason(DecisionReason.TOKEN_UNKNOWN, AuthChannel.PLUG_AND_CHARGE));
    }

    @Test
    void policyRefusalsMapToNoValidContractARevokedGrantToLocked() {
        List.of(DecisionReason.NO_GRANT, DecisionReason.OPERATOR_BLOCKED, DecisionReason.OPERATOR_NOT_ALLOWED, DecisionReason.PRICE_OVER_THRESHOLD)
                .forEach(reason -> assertEquals(OicpStatus.NO_VALID_CONTRACT, mapping.forReason(reason, AuthChannel.RFID), reason.name()));
        assertEquals(OicpStatus.EVCO_ID_LOCKED, mapping.forReason(DecisionReason.GRANT_REVOKED, AuthChannel.QR_CODE));
    }

    @Test
    void everyPlatformReasonHasAnAnswerOnEveryChannel() {
        for (DecisionReason reason : DecisionReason.values()) {
            for (AuthChannel channel : AuthChannel.values()) {
                mapping.forReason(reason, channel);
            }
        }
    }

    @Test
    void fallbacksAre021ForAMissedBudgetOrAFailedPolicyAnd022ForAMalformedRequest() {
        assertEquals(OicpStatus.SYSTEM_ERROR, mapping.policyTimeout());
        assertEquals(OicpStatus.SYSTEM_ERROR, mapping.policyFailed());
        assertEquals(OicpStatus.DATA_ERROR, mapping.malformedRequest());
        assertEquals(OicpStatus.SUCCESS, mapping.allowed());
    }

    @Test
    void aTableThatNamesACodeOutsideTheOicpListIsRefusedAtStartUp() {
        ProfileDocument bad = ProfileDocument.parse("bad", """
                allowed: "000"
                by_reason: {OTHER: "999"}
                fallback: {policy_timeout: "021", policy_failed: "021", malformed_request: "022"}
                """);
        assertThrows(IllegalArgumentException.class, () -> new AuthorizeStatusMapping(bad));
    }
}
