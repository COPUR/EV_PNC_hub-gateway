package com.evpnc.hub.hubject

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * OICP 2.3 wire shapes for the operations an EMP implements or calls, taken from the pinned contract
 * (`hubject-oicp-2.3-emp.openapi.yaml`, CC BY-SA 4.0, Hubject GmbH). Only the fields the adapter uses are modelled;
 * unknown fields are ignored on input, as OICP expects partners to tolerate additions.
 */
object OicpJson {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
}

@Serializable
data class OicpStatusCode(
    @SerialName("Code") val code: String,
    @SerialName("Description") val description: String? = null,
    @SerialName("AdditionalInfo") val additionalInfo: String? = null,
)

@Serializable
data class OicpHashedPin(
    @SerialName("Value") val value: String,
    @SerialName("Function") val function: String,
)

@Serializable
data class OicpRfidMifareFamily(@SerialName("UID") val uid: String)

@Serializable
data class OicpRfid(
    @SerialName("UID") val uid: String,
    @SerialName("RFID") val rfid: String,
    @SerialName("EvcoID") val evcoId: String? = null,
    @SerialName("PrintedNumber") val printedNumber: String? = null,
    @SerialName("ExpiryDate") val expiryDate: String? = null,
)

@Serializable
data class OicpQrCode(
    @SerialName("EvcoID") val evcoId: String,
    @SerialName("PIN") val pin: String? = null,
    @SerialName("HashedPIN") val hashedPin: OicpHashedPin? = null,
)

@Serializable
data class OicpEvco(@SerialName("EvcoID") val evcoId: String)

@Serializable
data class OicpIdentification(
    @SerialName("RFIDMifareFamilyIdentification") val rfidMifareFamily: OicpRfidMifareFamily? = null,
    @SerialName("RFIDIdentification") val rfid: OicpRfid? = null,
    @SerialName("QRCodeIdentification") val qrCode: OicpQrCode? = null,
    @SerialName("PlugAndChargeIdentification") val plugAndCharge: OicpEvco? = null,
    @SerialName("RemoteIdentification") val remote: OicpEvco? = null,
)

@Serializable
data class AuthorizeStartRequest(
    @SerialName("OperatorID") val operatorId: String,
    @SerialName("Identification") val identification: OicpIdentification,
    @SerialName("SessionID") val sessionId: String? = null,
    @SerialName("CPOPartnerSessionID") val cpoPartnerSessionId: String? = null,
    @SerialName("EMPPartnerSessionID") val empPartnerSessionId: String? = null,
    @SerialName("EvseID") val evseId: String? = null,
    @SerialName("PartnerProductID") val partnerProductId: String? = null,
)

@Serializable
data class AuthorizeStartResponse(
    @SerialName("AuthorizationStatus") val authorizationStatus: String,
    @SerialName("StatusCode") val statusCode: OicpStatusCode,
    @SerialName("SessionID") val sessionId: String? = null,
    @SerialName("CPOPartnerSessionID") val cpoPartnerSessionId: String? = null,
    @SerialName("EMPPartnerSessionID") val empPartnerSessionId: String? = null,
    @SerialName("ProviderID") val providerId: String? = null,
    @SerialName("AuthorizationStopIdentifications") val stopIdentifications: List<OicpIdentification>? = null,
)

@Serializable
data class AuthorizeRemoteStartRequest(
    @SerialName("ProviderID") val providerId: String,
    @SerialName("EvseID") val evseId: String,
    @SerialName("Identification") val identification: OicpIdentification,
    /** Left empty on the first request: Hubject assigns it (RH-HUBJECT-ERR-004). */
    @SerialName("SessionID") val sessionId: String? = null,
    @SerialName("EMPPartnerSessionID") val empPartnerSessionId: String? = null,
    @SerialName("PartnerProductID") val partnerProductId: String? = null,
)

@Serializable
data class AuthorizeRemoteStopRequest(
    @SerialName("SessionID") val sessionId: String,
    @SerialName("ProviderID") val providerId: String,
    @SerialName("EvseID") val evseId: String,
    @SerialName("EMPPartnerSessionID") val empPartnerSessionId: String? = null,
)

/** The `eRoamingAcknowledgement` answer used by remote start/stop, CDR and notification operations. */
@Serializable
data class OicpAcknowledgement(
    @SerialName("Result") val result: Boolean,
    @SerialName("StatusCode") val statusCode: OicpStatusCode,
    @SerialName("SessionID") val sessionId: String? = null,
    @SerialName("CPOPartnerSessionID") val cpoPartnerSessionId: String? = null,
    @SerialName("EMPPartnerSessionID") val empPartnerSessionId: String? = null,
)

@Serializable
data class OicpSignedMeteringValue(
    @SerialName("SignedMeteringValue") val value: String,
    @SerialName("MeteringStatus") val status: String,
)

@Serializable
data class OicpCalibrationLawVerificationInfo(
    @SerialName("CalibrationLawCertificateID") val certificateId: String? = null,
    @SerialName("PublicKey") val publicKey: String? = null,
    @SerialName("MeteringSignatureUrl") val meteringSignatureUrl: String? = null,
    @SerialName("MeteringSignatureEncodingFormat") val encodingFormat: String? = null,
    @SerialName("SignedMeteringValuesVerificationInstruction") val verificationInstruction: String? = null,
)

@Serializable
data class OicpChargeDetailRecord(
    @SerialName("SessionID") val sessionId: String,
    @SerialName("EvseID") val evseId: String,
    @SerialName("Identification") val identification: OicpIdentification,
    @SerialName("ChargingStart") val chargingStart: String,
    @SerialName("ChargingEnd") val chargingEnd: String,
    @SerialName("SessionStart") val sessionStart: String,
    @SerialName("SessionEnd") val sessionEnd: String,
    @SerialName("ConsumedEnergy") val consumedEnergy: Double,
    @SerialName("CPOPartnerSessionID") val cpoPartnerSessionId: String? = null,
    @SerialName("EMPPartnerSessionID") val empPartnerSessionId: String? = null,
    @SerialName("PartnerProductID") val partnerProductId: String? = null,
    @SerialName("MeterValueStart") val meterValueStart: Double? = null,
    @SerialName("MeterValueEnd") val meterValueEnd: Double? = null,
    @SerialName("SignedMeteringValues") val signedMeteringValues: List<OicpSignedMeteringValue>? = null,
    @SerialName("CalibrationLawVerificationInfo") val calibration: OicpCalibrationLawVerificationInfo? = null,
    @SerialName("HubOperatorID") val hubOperatorId: String? = null,
    @SerialName("HubProviderId") val hubProviderId: String? = null,
)
