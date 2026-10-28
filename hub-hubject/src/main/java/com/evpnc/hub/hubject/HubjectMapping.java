package com.evpnc.hub.hubject;

import com.evpnc.hub.core.AuthChannel;
import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.HubCdr;
import com.evpnc.hub.core.Json;
import com.evpnc.hub.core.PricedBy;
import com.evpnc.hub.core.Secret;
import com.evpnc.hub.core.TokenRef;
import com.evpnc.hub.core.TokenType;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** OICP identification -> platform token, and back. Patterns come from the pinned contract. */
public final class HubjectMapping {
    private static final Pattern UID = Pattern.compile("^([0-9A-F]{8}|[0-9A-F]{14}|[0-9A-F]{20})$");
    public static final Pattern EVCO_ID = Pattern.compile(
            "^(([A-Za-z]{2}-?[A-Za-z0-9]{3}-?C[A-Za-z0-9]{8}-?[\\d|A-Za-z])|([A-Za-z]{2}[*|-]?[A-Za-z0-9]{3}[*|-]?[A-Za-z0-9]{6}[*|-]?[\\d|X]))$");
    public static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9]{8}(-[A-Za-z0-9]{4}){3}-[A-Za-z0-9]{12}$");

    private HubjectMapping() {}

    /** @param pin null when the identification carries none */
    public record Identified(AuthChannel channel, TokenRef token, Secret pin) {}

    public sealed interface IdentificationResult {
        record Ok(Identified identified) implements IdentificationResult {}

        /** The identification is unusable; {@code channel} (null when unknown) says which refusal code fits. */
        record Invalid(AuthChannel channel, String problem) implements IdentificationResult {}
    }

    /** Exactly one variant must be present. A UID or EvcoID that breaks the contract's pattern is refused, not guessed at. */
    public static IdentificationResult identify(OicpWire.Identification identification) {
        List<Object> present = new ArrayList<>();
        for (Object variant : new Object[] {
            identification.rfidMifareFamily(), identification.rfid(), identification.qrCode(),
            identification.plugAndCharge(), identification.remote()}) {
            if (variant != null) {
                present.add(variant);
            }
        }
        if (present.size() != 1) {
            return new IdentificationResult.Invalid(null, "expected exactly one identification variant, got " + present.size());
        }
        Object only = present.get(0);
        if (only instanceof OicpWire.RfidMifareFamily family) {
            return rfid(family.uid(), null);
        }
        if (only instanceof OicpWire.Rfid rfid) {
            return rfid(rfid.uid(), rfid.evcoId());
        }
        if (only instanceof OicpWire.QrCode qr) {
            if (!EVCO_ID.matcher(qr.evcoId()).matches()) {
                return new IdentificationResult.Invalid(AuthChannel.QR_CODE, "EvcoID breaks the contract pattern");
            }
            String pinText = qr.pin() != null ? qr.pin() : qr.hashedPin() != null ? qr.hashedPin().value() : null;
            Secret pin = pinText == null ? null : new Secret(pinText);
            return new IdentificationResult.Ok(new Identified(AuthChannel.QR_CODE, new TokenRef(qr.evcoId(), TokenType.APP_USER, qr.evcoId()), pin));
        }
        // The two remaining variants are both OicpWire.Evco; tell them apart by the field they came from.
        if (identification.plugAndCharge() != null) {
            return evco(identification.plugAndCharge().evcoId(), AuthChannel.PLUG_AND_CHARGE, TokenType.EMAID);
        }
        return evco(identification.remote().evcoId(), AuthChannel.REMOTE, TokenType.APP_USER);
    }

    private static IdentificationResult rfid(String uid, String evcoId) {
        if (!UID.matcher(uid).matches()) {
            return new IdentificationResult.Invalid(AuthChannel.RFID, "UID breaks the contract pattern");
        }
        return new IdentificationResult.Ok(new Identified(AuthChannel.RFID, new TokenRef(uid, TokenType.RFID, evcoId), null));
    }

    private static IdentificationResult evco(String evcoId, AuthChannel channel, TokenType type) {
        if (!EVCO_ID.matcher(evcoId).matches()) {
            return new IdentificationResult.Invalid(channel, "EvcoID breaks the contract pattern");
        }
        return new IdentificationResult.Ok(new Identified(channel, new TokenRef(evcoId, type, evcoId), null));
    }

    /** Identification we send to Hubject (remote start) or list as allowed to stop. */
    public static OicpWire.Identification identification(TokenRef token) {
        String evco = token.contractId() != null ? token.contractId() : token.uid();
        if (token.type() == TokenType.RFID && UID.matcher(token.uid()).matches()) {
            return OicpWire.Identification.ofRfidMifareFamily(token.uid());
        }
        if (token.type() == TokenType.EMAID) {
            return OicpWire.Identification.ofPlugAndCharge(evco);
        }
        return OicpWire.Identification.ofRemote(evco);
    }

    /** OICP timestamps carry an offset (TR is +03:00); they are stored as instants. @return null when unreadable */
    public static Instant instant(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * OICP CDR -> platform CDR. Never throws on content: a record Hubject will not resend must not be lost to a parsing
     * problem, so unreadable fields become null and the original values stay under {@code extensions.oicp}.
     */
    public static HubCdr cdr(OicpWire.ChargeDetailRecord record) {
        TokenRef token = identify(record.identification()) instanceof IdentificationResult.Ok ok ? ok.identified().token() : null;

        ObjectNode oicp = Json.object();
        oicp.put("session_start", record.sessionStart());
        oicp.put("session_end", record.sessionEnd());
        oicp.put("charging_start", record.chargingStart());
        oicp.put("charging_end", record.chargingEnd());
        putIfPresent(oicp, "partner_product_id", record.partnerProductId());
        putIfPresent(oicp, "hub_operator_id", record.hubOperatorId());
        putIfPresent(oicp, "hub_provider_id", record.hubProviderId());
        putIfPresent(oicp, "emp_partner_session_id", record.empPartnerSessionId());
        putIfPresent(oicp, "cpo_partner_session_id", record.cpoPartnerSessionId());
        if (record.signedMeteringValues() != null) {
            ArrayNode values = Json.array();
            for (OicpWire.SignedMeteringValue v : record.signedMeteringValues()) {
                ObjectNode value = Json.object();
                value.put("status", v.status());
                value.put("value", v.value());
                values.add(value);
            }
            oicp.set("signed_metering", values);
        }
        OicpWire.CalibrationLawVerificationInfo c = record.calibration();
        if (c != null) {
            ObjectNode calibration = Json.object();
            putIfPresent(calibration, "certificate_id", c.certificateId());
            putIfPresent(calibration, "public_key", c.publicKey());
            putIfPresent(calibration, "metering_signature_url", c.meteringSignatureUrl());
            putIfPresent(calibration, "encoding_format", c.encodingFormat());
            putIfPresent(calibration, "verification_instruction", c.verificationInstruction());
            oicp.set("calibration_law", calibration);
        }
        ObjectNode extensions = Json.object();
        extensions.set("oicp", oicp);

        return new HubCdr(Hub.HUBJECT, record.sessionId(), record.evseId(), token,
                instant(record.sessionStart()), instant(record.sessionEnd()), record.consumedEnergy(), PricedBy.NOBODY)
                .withCharging(instant(record.chargingStart()), instant(record.chargingEnd()))
                .withMeter(record.meterValueStart(), record.meterValueEnd())
                .withExtensions(extensions);
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
