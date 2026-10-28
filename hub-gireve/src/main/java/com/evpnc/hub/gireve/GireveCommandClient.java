package com.evpnc.hub.gireve;

import com.evpnc.hub.core.FailureClass;
import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.HttpRequestSpec;
import com.evpnc.hub.core.HttpResponseSpec;
import com.evpnc.hub.core.HubException;
import com.evpnc.hub.core.HubTransport;
import com.evpnc.hub.core.Json;
import com.evpnc.hub.core.RemoteRejection;
import com.evpnc.hub.core.RemoteResult;
import com.evpnc.hub.core.RemoteSessionPort;
import com.evpnc.hub.core.RemoteStart;
import com.evpnc.hub.core.RemoteStop;
import com.evpnc.hub.core.TransportTimeout;
import com.evpnc.hub.core.ocpi221.Ocpi221Compat;
import com.evpnc.hub.core.ocpi221.OcpiHeaders;
import com.evpnc.hub.core.ocpi221.OcpiObject;
import com.evpnc.hub.core.ocpi221.OcpiParty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;

/**
 * START_SESSION and STOP_SESSION through the IOP. {@code ACCEPTED} only means the IOP took the command; the real result
 * arrives later at our callback as a CommandResult, and if none arrives within {@code CommandResponse.timeout} the
 * command may never have reached the charge point (RH-GIREVE-ERR-003). Reserve, cancel and unlock are refused before any
 * call (CMD-002).
 */
public final class GireveCommandClient implements RemoteSessionPort {
    private final GireveConfig config;
    private final GireveProfile profile;
    private final Ocpi221Compat compat;
    private final HubTransport transport;
    private final Clock clock;

    public GireveCommandClient(GireveConfig config, GireveProfile profile, Ocpi221Compat compat, HubTransport transport, Clock clock) {
        this.config = config;
        this.profile = profile;
        this.compat = compat;
        this.transport = transport;
        this.clock = clock;
    }

    @Override
    public RemoteResult start(RemoteStart command) {
        compat.checkCommand("START_SESSION");
        if (command.locationId() == null) {
            throw new HubException(Hub.GIREVE, FailureClass.NON_RETRYABLE, "LOCATION_REQUIRED", "START_SESSION needs the CPO's location_id");
        }
        ObjectNode wire = Json.object();
        wire.put("response_url", config.callbackBase() + "/START_SESSION/" + command.requestId());
        wire.set("token", GireveAuthorizeHandler.canonicalToken(config, command.token(), clock.instant().toString()));
        wire.put("location_id", command.locationId());
        wire.put("evse_uid", command.evseId());
        if (command.connectorId() != null) {
            wire.put("connector_id", command.connectorId());
        }
        return send("START_SESSION", command.requestId(), compat.outbound(OcpiObject.START_SESSION, wire));
    }

    @Override
    public RemoteResult stop(RemoteStop command) {
        compat.checkCommand("STOP_SESSION");
        ObjectNode body = Json.object();
        body.put("response_url", config.callbackBase() + "/STOP_SESSION/" + command.hubSessionId());
        body.put("session_id", command.hubSessionId());
        return send("STOP_SESSION", command.hubSessionId(), body);
    }

    private RemoteResult send(String type, String requestId, ObjectNode body) {
        OcpiParty hub = profile.hubParty(config.environment());
        HttpRequestSpec request = new HttpRequestSpec(
                "POST",
                config.commandsPath() + "/" + type,
                OcpiHeaders.request("commands", config.ourParty(), hub, config.tokenForHub().reveal(), requestId, requestId),
                body.toString());
        HttpResponseSpec response;
        try {
            response = transport.send(request);
        } catch (TransportTimeout e) {
            return new RemoteResult.UnknownState("no answer from the IOP: " + e.getMessage());
        }
        if (profile.waitStatuses().contains(response.status()) || response.status() >= 500) {
            throw new HubException(Hub.GIREVE, FailureClass.RETRYABLE, Integer.toString(response.status()), "IOP asked us to wait or failed");
        }
        if (response.status() < 200 || response.status() > 299) {
            throw new HubException(Hub.GIREVE, FailureClass.NON_RETRYABLE, Integer.toString(response.status()), "IOP refused the command");
        }
        OcpiResponse envelope = OcpiResponse.parse(response.body());
        if (envelope == null) {
            throw new HubException(Hub.GIREVE, FailureClass.NON_RETRYABLE, null, "answer is not an OCPI response");
        }
        int code = envelope.statusCode();
        if (code == OcpiStatusCodes.SUCCESS) {
            return commandResult(envelope);
        }
        if (code == OcpiStatusCodes.UNKNOWN_RECEIVER) {
            return new RemoteResult.Rejected("4001", RemoteRejection.PARTNER_UNKNOWN, envelope.message());
        }
        if (code == OcpiStatusCodes.RECEIVER_NOT_CONNECTED) {
            return new RemoteResult.Rejected("4003", RemoteRejection.PARTNER_UNREACHABLE, envelope.message());
        }
        if (code == OcpiStatusCodes.FORWARDED_REQUEST_TIMEOUT) {
            return new RemoteResult.UnknownState("IOP timed out forwarding the command (4002)");
        }
        if (code >= 3000 && code <= 3999) {
            throw new HubException(Hub.GIREVE, FailureClass.RETRYABLE, Integer.toString(code), "IOP server error");
        }
        throw new HubException(Hub.GIREVE, FailureClass.NON_RETRYABLE, Integer.toString(code),
                envelope.message() != null ? envelope.message() : "client error");
    }

    private static RemoteResult commandResult(OcpiResponse envelope) {
        JsonNode data = envelope.data();
        String result = data != null && data.isObject() ? Json.text(data, "result") : null;
        if ("ACCEPTED".equals(result)) {
            return new RemoteResult.Accepted(null);
        }
        if ("REJECTED".equals(result) || "NOT_SUPPORTED".equals(result) || "UNKNOWN_SESSION".equals(result)) {
            return new RemoteResult.Rejected(result, RemoteRejection.OTHER);
        }
        throw new HubException(Hub.GIREVE, FailureClass.NON_RETRYABLE, null, "unknown command result '" + result + "'");
    }
}
