package com.evpnc.hub.hubject;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * OICP 2.3 wire shapes for the operations an EMP implements or calls, taken from the pinned contract
 * ({@code hubject-oicp-2.3-emp.openapi.yaml}, CC BY-SA 4.0, Hubject GmbH). Only the fields the adapter uses are
 * modelled; unknown fields are ignored on input, as OICP expects partners to tolerate additions. Optional fields are
 * null when absent.
 */
public final class OicpWire {
    private OicpWire() {}

    public record StatusCode(
            @JsonProperty(value = "Code", required = true) String code,
            @JsonProperty("Description") String description,
            @JsonProperty("AdditionalInfo") String additionalInfo) {}

    public record HashedPin(
            @JsonProperty(value = "Value", required = true) String value,
            @JsonProperty(value = "Function", required = true) String function) {}

    public record RfidMifareFamily(@JsonProperty(value = "UID", required = true) String uid) {}

    public record Rfid(
            @JsonProperty(value = "UID", required = true) String uid,
            @JsonProperty(value = "RFID", required = true) String rfid,
            @JsonProperty("EvcoID") String evcoId,
            @JsonProperty("PrintedNumber") String printedNumber,
            @JsonProperty("ExpiryDate") String expiryDate) {}

    public record QrCode(
            @JsonProperty(value = "EvcoID", required = true) String evcoId,
            @JsonProperty("PIN") String pin,
            @JsonProperty("HashedPIN") HashedPin hashedPin) {}

    public record Evco(@JsonProperty(value = "EvcoID", required = true) String evcoId) {}

    public record Identification(
            @JsonProperty("RFIDMifareFamilyIdentification") RfidMifareFamily rfidMifareFamily,
            @JsonProperty("RFIDIdentification") Rfid rfid,
            @JsonProperty("QRCodeIdentification") QrCode qrCode,
            @JsonProperty("PlugAndChargeIdentification") Evco plugAndCharge,
            @JsonProperty("RemoteIdentification") Evco remote) {

        public static Identification ofRfidMifareFamily(String uid) {
            return new Identification(new RfidMifareFamily(uid), null, null, null, null);
        }

        public static Identification ofPlugAndCharge(String evcoId) {
            return new Identification(null, null, null, new Evco(evcoId), null);
        }

        public static Identification ofRemote(String evcoId) {
            return new Identification(null, null, null, null, new Evco(evcoId));
        }
    }

    public record AuthorizeStartRequest(
            @JsonProperty(value = "OperatorID", required = true) String operatorId,
            @JsonProperty(value = "Identification", required = true) Identification identification,
            @JsonProperty("SessionID") String sessionId,
            @JsonProperty("CPOPartnerSessionID") String cpoPartnerSessionId,
            @JsonProperty("EMPPartnerSessionID") String empPartnerSessionId,
            @JsonProperty("EvseID") String evseId,
            @JsonProperty("PartnerProductID") String partnerProductId) {}

    public record AuthorizeStartResponse(
            @JsonProperty(value = "AuthorizationStatus", required = true) String authorizationStatus,
            @JsonProperty(value = "StatusCode", required = true) StatusCode statusCode,
            @JsonProperty("SessionID") String sessionId,
            @JsonProperty("CPOPartnerSessionID") String cpoPartnerSessionId,
            @JsonProperty("EMPPartnerSessionID") String empPartnerSessionId,
            @JsonProperty("ProviderID") String providerId,
            @JsonProperty("AuthorizationStopIdentifications") List<Identification> stopIdentifications) {

        public AuthorizeStartResponse(String authorizationStatus, StatusCode statusCode) {
            this(authorizationStatus, statusCode, null, null, null, null, null);
        }
    }

    /** @param sessionId left empty on the first request: Hubject assigns it (RH-HUBJECT-ERR-004) */
    public record AuthorizeRemoteStartRequest(
            @JsonProperty(value = "ProviderID", required = true) String providerId,
            @JsonProperty(value = "EvseID", required = true) String evseId,
            @JsonProperty(value = "Identification", required = true) Identification identification,
            @JsonProperty("SessionID") String sessionId,
            @JsonProperty("EMPPartnerSessionID") String empPartnerSessionId,
            @JsonProperty("PartnerProductID") String partnerProductId) {}

    public record AuthorizeRemoteStopRequest(
            @JsonProperty(value = "SessionID", required = true) String sessionId,
            @JsonProperty(value = "ProviderID", required = true) String providerId,
            @JsonProperty(value = "EvseID", required = true) String evseId,
            @JsonProperty("EMPPartnerSessionID") String empPartnerSessionId) {}

    /** The {@code eRoamingAcknowledgement} answer used by remote start/stop, CDR and notification operations. */
    public record Acknowledgement(
            @JsonProperty(value = "Result", required = true) boolean result,
            @JsonProperty(value = "StatusCode", required = true) StatusCode statusCode,
            @JsonProperty("SessionID") String sessionId,
            @JsonProperty("CPOPartnerSessionID") String cpoPartnerSessionId,
            @JsonProperty("EMPPartnerSessionID") String empPartnerSessionId) {

        public Acknowledgement(boolean result, StatusCode statusCode) {
            this(result, statusCode, null, null, null);
        }
    }

    public record SignedMeteringValue(
            @JsonProperty(value = "SignedMeteringValue", required = true) String value,
            @JsonProperty(value = "MeteringStatus", required = true) String status) {}

    public record CalibrationLawVerificationInfo(
            @JsonProperty("CalibrationLawCertificateID") String certificateId,
            @JsonProperty("PublicKey") String publicKey,
            @JsonProperty("MeteringSignatureUrl") String meteringSignatureUrl,
            @JsonProperty("MeteringSignatureEncodingFormat") String encodingFormat,
            @JsonProperty("SignedMeteringValuesVerificationInstruction") String verificationInstruction) {}

    public record ChargeDetailRecord(
            @JsonProperty(value = "SessionID", required = true) String sessionId,
            @JsonProperty(value = "EvseID", required = true) String evseId,
            @JsonProperty(value = "Identification", required = true) Identification identification,
            @JsonProperty(value = "ChargingStart", required = true) String chargingStart,
            @JsonProperty(value = "ChargingEnd", required = true) String chargingEnd,
            @JsonProperty(value = "SessionStart", required = true) String sessionStart,
            @JsonProperty(value = "SessionEnd", required = true) String sessionEnd,
            @JsonProperty(value = "ConsumedEnergy", required = true) double consumedEnergy,
            @JsonProperty("CPOPartnerSessionID") String cpoPartnerSessionId,
            @JsonProperty("EMPPartnerSessionID") String empPartnerSessionId,
            @JsonProperty("PartnerProductID") String partnerProductId,
            @JsonProperty("MeterValueStart") Double meterValueStart,
            @JsonProperty("MeterValueEnd") Double meterValueEnd,
            @JsonProperty("SignedMeteringValues") List<SignedMeteringValue> signedMeteringValues,
            @JsonProperty("CalibrationLawVerificationInfo") CalibrationLawVerificationInfo calibration,
            @JsonProperty("HubOperatorID") String hubOperatorId,
            @JsonProperty("HubProviderId") String hubProviderId) {}
}
