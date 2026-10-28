package com.evpnc.hub.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Objects;

/**
 * A charge detail record as received from a hub, normalised just enough for the platform. Hubject CDRs carry no price
 * (the payments service prices them); GIREVE CDRs are full OCPI and arrive in {@code ocpi} already converted to the
 * canonical 2.3.0 shape.
 *
 * @param hubSessionId hub session id; with {@code hub} it is the idempotency key (RH-HUBJECT-ERR-006)
 * @param ocpi the canonical OCPI CDR, or null
 * @param extensions hub data with no canonical home, under {@code extensions.<hub>} like the canonical model does
 */
public record HubCdr(
        Hub hub,
        String hubSessionId,
        String evseId,
        TokenRef token,
        Instant sessionStart,
        Instant sessionEnd,
        Double energyKwh,
        PricedBy pricedBy,
        ObjectNode ocpi,
        ObjectNode extensions,
        Instant chargingStart,
        Instant chargingEnd,
        Double meterStartKwh,
        Double meterEndKwh) {

    public HubCdr {
        Objects.requireNonNull(hub);
        Objects.requireNonNull(hubSessionId);
        Objects.requireNonNull(pricedBy);
        Objects.requireNonNull(extensions);
    }

    public HubCdr(
            Hub hub,
            String hubSessionId,
            String evseId,
            TokenRef token,
            Instant sessionStart,
            Instant sessionEnd,
            Double energyKwh,
            PricedBy pricedBy) {
        this(hub, hubSessionId, evseId, token, sessionStart, sessionEnd, energyKwh, pricedBy, null,
                JsonNodeFactory.instance.objectNode(), null, null, null, null);
    }

    public HubCdr withOcpi(ObjectNode value) {
        return new HubCdr(hub, hubSessionId, evseId, token, sessionStart, sessionEnd, energyKwh, pricedBy, value,
                extensions, chargingStart, chargingEnd, meterStartKwh, meterEndKwh);
    }

    public HubCdr withExtensions(ObjectNode value) {
        return new HubCdr(hub, hubSessionId, evseId, token, sessionStart, sessionEnd, energyKwh, pricedBy, ocpi,
                value, chargingStart, chargingEnd, meterStartKwh, meterEndKwh);
    }

    public HubCdr withCharging(Instant start, Instant end) {
        return new HubCdr(hub, hubSessionId, evseId, token, sessionStart, sessionEnd, energyKwh, pricedBy, ocpi,
                extensions, start, end, meterStartKwh, meterEndKwh);
    }

    public HubCdr withMeter(Double startKwh, Double endKwh) {
        return new HubCdr(hub, hubSessionId, evseId, token, sessionStart, sessionEnd, energyKwh, pricedBy, ocpi,
                extensions, chargingStart, chargingEnd, startKwh, endKwh);
    }
}
