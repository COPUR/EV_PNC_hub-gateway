package com.evpnc.hub.hubject;

import com.evpnc.hub.core.FailureClass;
import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.HttpRequestSpec;
import com.evpnc.hub.core.HttpResponseSpec;
import com.evpnc.hub.core.HubException;
import com.evpnc.hub.core.HubTransport;
import com.evpnc.hub.core.RemoteRejection;
import com.evpnc.hub.core.RemoteResult;
import com.evpnc.hub.core.RemoteSessionPort;
import com.evpnc.hub.core.RemoteStart;
import com.evpnc.hub.core.RemoteStop;
import com.evpnc.hub.core.TransportTimeout;
import com.evpnc.hub.core.resilience.Throttler;
import java.util.Map;

/**
 * Remote start and stop through the Hubject HBS. Requests are built as data and sent by a {@link HubTransport}, so the
 * mTLS client is replaceable and the logic is testable without a network (and without touching a live system).
 *
 * <p>Hubject decides success from {@code Result} and the StatusCode, not from the HTTP status (RH-HUBJECT-ERR-002). A
 * timeout is not a failure: the CPO may have started anyway, so the state is unknown until a notification, status or
 * CDR says otherwise (RH-HUBJECT-ERR-005). The caller's transport must use
 * {@link HubjectProfile#remoteClientTimeout()} (65 s).
 */
public final class HubjectRemoteClient implements RemoteSessionPort {
    private static final Map<OicpStatus, RemoteRejection> REJECTIONS = Map.ofEntries(
            Map.entry(OicpStatus.NO_VALID_CONTRACT, RemoteRejection.NO_VALID_CONTRACT),
            Map.entry(OicpStatus.PARTNER_NOT_FOUND, RemoteRejection.PARTNER_UNKNOWN),
            Map.entry(OicpStatus.PARTNER_DID_NOT_RESPOND, RemoteRejection.PARTNER_UNREACHABLE),
            Map.entry(OicpStatus.SERVICE_NOT_AVAILABLE, RemoteRejection.PARTNER_UNREACHABLE),
            Map.entry(OicpStatus.EVSE_COMMUNICATION_FAILED, RemoteRejection.EVSE_UNREACHABLE),
            Map.entry(OicpStatus.NO_EV_CONNECTED, RemoteRejection.NO_VEHICLE_CONNECTED),
            Map.entry(OicpStatus.EVSE_ALREADY_RESERVED, RemoteRejection.EVSE_RESERVED),
            Map.entry(OicpStatus.EVSE_IN_USE, RemoteRejection.EVSE_IN_USE),
            Map.entry(OicpStatus.UNKNOWN_EVSE_ID, RemoteRejection.EVSE_UNKNOWN),
            Map.entry(OicpStatus.EVSE_NOT_HUBJECT_COMPATIBLE, RemoteRejection.EVSE_NOT_ROAMABLE),
            Map.entry(OicpStatus.EVSE_OUT_OF_SERVICE, RemoteRejection.EVSE_OUT_OF_SERVICE));

    private final String providerId;
    private final HubTransport transport;
    private final Throttler throttler;

    public HubjectRemoteClient(String providerId, HubTransport transport, Throttler throttler) {
        this.providerId = providerId;
        this.transport = transport;
        this.throttler = throttler;
    }

    @Override
    public RemoteResult start(RemoteStart command) {
        OicpWire.AuthorizeRemoteStartRequest body = new OicpWire.AuthorizeRemoteStartRequest(
                providerId,
                command.evseId(),
                HubjectMapping.identification(command.token()),
                null,
                command.requestId(),
                null);
        return send("AuthorizeRemoteStart", "/charging/v21/providers/" + providerId + "/authorize-remote/start", OicpJson.write(body));
    }

    @Override
    public RemoteResult stop(RemoteStop command) {
        OicpWire.AuthorizeRemoteStopRequest body =
                new OicpWire.AuthorizeRemoteStopRequest(command.hubSessionId(), providerId, command.evseId(), null);
        // The pinned contract names the path parameter externalID; the leading guide fills it with the ProviderID.
        return send("AuthorizeRemoteStop", "/charging/v21/providers/" + providerId + "/authorize-remote/stop", OicpJson.write(body));
    }

    private RemoteResult send(String operation, String path, String body) {
        if (!throttler.tryAcquire(operation)) {
            throw new HubException(Hub.HUBJECT, FailureClass.RETRYABLE, "THROTTLED",
                    operation + " over the Acceptable Use Policy limit; retry in " + throttler.waitTime(operation));
        }
        HttpResponseSpec response;
        try {
            response = transport.send(new HttpRequestSpec("POST", path, Map.of("Content-Type", "application/json"), body));
        } catch (TransportTimeout e) {
            return new RemoteResult.UnknownState("no answer from the HBS: " + e.getMessage());
        }
        OicpWire.Acknowledgement ack = parse(response);
        if (ack.result()) {
            return new RemoteResult.Accepted(ack.sessionId());
        }
        return rejection(ack.statusCode());
    }

    private OicpWire.Acknowledgement parse(HttpResponseSpec response) {
        if (response.status() == 429) {
            throw new HubException(Hub.HUBJECT, FailureClass.RETRYABLE, "429", "rate limited by the HBS");
        }
        if (response.status() >= 500) {
            throw new HubException(Hub.HUBJECT, FailureClass.RETRYABLE, Integer.toString(response.status()), "HBS error");
        }
        if (response.status() != 200) {
            throw new HubException(Hub.HUBJECT, FailureClass.NON_RETRYABLE, Integer.toString(response.status()), "HBS refused the request");
        }
        try {
            return OicpJson.read(response.body() == null ? "" : response.body(), OicpWire.Acknowledgement.class);
        } catch (OicpJson.WireException e) {
            throw new HubException(Hub.HUBJECT, FailureClass.NON_RETRYABLE, null, "answer is not an OICP acknowledgement", e);
        }
    }

    private RemoteResult rejection(OicpWire.StatusCode status) {
        OicpStatus known = OicpStatus.lookup(status.code());
        if (known == OicpStatus.UNAUTHORIZED_ACCESS) {
            // 017: wrong or expired client certificate. Retrying cannot help; a human must renew it (RH-HUBJECT-ERR-003).
            throw new HubException(Hub.HUBJECT, FailureClass.CONFIGURATION, status.code(), "certificate or ProviderID rejected (017)");
        }
        RemoteRejection reason = known == null ? RemoteRejection.OTHER : REJECTIONS.getOrDefault(known, RemoteRejection.OTHER);
        return new RemoteResult.Rejected(status.code(), reason, status.additionalInfo());
    }
}
