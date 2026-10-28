package com.evpnc.hub.hubject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evpnc.hub.core.FakeTransport;
import com.evpnc.hub.core.Hub;
import com.evpnc.hub.core.RemoteStop;
import com.evpnc.hub.core.resilience.Throttler;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Characterization of the legacy surface: the pinned OICP 2.3 EMP contract. These tests pin what we claim to implement
 * against the vendor file itself, so a contract bump (fetch script {@code --update-pins}) that moves a path, drops a
 * field or adds a status code fails here, in review, and not in the Hubject QA protocol.
 */
class OicpContractTest {
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> operation(String path) {
        Map<String, Object> paths = map(TestSupport.PinnedContracts.oicpEmp().get("paths"));
        Object item = paths.get(path);
        if (item == null) {
            throw new IllegalStateException("pinned contract has no path " + path);
        }
        return map(map(item).get("post"));
    }

    private static Map<String, Object> requestSchema(String path) {
        Map<String, Object> body = map(operation(path).get("requestBody"));
        return map(map(map(body.get("content")).get("application/json")).get("schema"));
    }

    private static Map<String, Object> responseSchema(String path) {
        Map<String, Object> ok = map(map(operation(path).get("responses")).get("200"));
        return map(map(map(ok.get("content")).get("application/json")).get("schema"));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> required(Map<String, Object> schema) {
        Object required = schema.get("required");
        return required == null ? Set.of() : new HashSet<>((List<String>) required);
    }

    /** The JSON names of a wire record, from its {@code @JsonProperty} annotations. */
    private static Set<String> modelled(Class<?> wire) {
        Set<String> names = new HashSet<>();
        for (RecordComponent component : wire.getRecordComponents()) {
            names.add(component.getAccessor().getAnnotation(JsonProperty.class).value());
        }
        return names;
    }

    /** Every JSON name we model exists in the contract, and every required contract field is modelled. */
    private static void assertFieldsMatch(String path, Map<String, Object> schema, Class<?> wire) {
        Set<String> modelled = modelled(wire);
        Set<String> contract = map(schema.get("properties")).keySet();
        Set<String> notInContract = new HashSet<>(modelled);
        notInContract.removeAll(contract);
        assertTrue(contract.containsAll(modelled), path + ": modelled but not in the contract: " + notInContract);
        Set<String> notModelled = new HashSet<>(required(schema));
        notModelled.removeAll(modelled);
        assertTrue(modelled.containsAll(required(schema)), path + ": required by the contract but not modelled: " + notModelled);
    }

    @Test
    void authorizeStartRequestAndResponseMatchThePinnedContract() {
        String path = "/charging/v21/operators/{operatorID}/authorize/start";
        assertFieldsMatch(path + " request", requestSchema(path), OicpWire.AuthorizeStartRequest.class);
        assertFieldsMatch(path + " response", responseSchema(path), OicpWire.AuthorizeStartResponse.class);
    }

    @Test
    void remoteStartAndStopRequestsMatchThePinnedContract() {
        String start = "/charging/v21/providers/{providerID}/authorize-remote/start";
        String stop = "/charging/v21/providers/{externalID}/authorize-remote/stop";
        assertFieldsMatch(start, requestSchema(start), OicpWire.AuthorizeRemoteStartRequest.class);
        assertFieldsMatch(stop, requestSchema(stop), OicpWire.AuthorizeRemoteStopRequest.class);
        assertFieldsMatch(start + " response", responseSchema(start), OicpWire.Acknowledgement.class);
    }

    @Test
    void chargeDetailRecordMatchesThePinnedContract() {
        String path = "/cdrmgmt/v22/operators/{operatorID}/charge-detail-record";
        assertFieldsMatch(path, requestSchema(path), OicpWire.ChargeDetailRecord.class);
    }

    @Test
    void theRemoteStopPathParameterIsNamedExternalIdInTheContractAndFilledWithTheProviderId() {
        // Surprise recorded in docs/refactoring-report.md: the pinned file says externalID, the leading guide says ProviderID.
        operation("/charging/v21/providers/{externalID}/authorize-remote/stop");
        FakeTransport transport = new FakeTransport();
        transport.answers(200, "{\"Result\":true,\"StatusCode\":{\"Code\":\"000\"}}");
        new HubjectRemoteClient("TRXYZ", transport, new Throttler(Map.of(), TestSupport.newClock()))
                .stop(new RemoteStop(Hub.HUBJECT, "b2688855-7f00-0002-6d8e-48d883f6abb6", "TR*ZZZ*E0001*1"));
        assertEquals("/charging/v21/providers/TRXYZ/authorize-remote/stop", transport.sent.get(0).path());
    }

    @Test
    void ourStatusCodeCatalogueEqualsTheContractsList() {
        Map<String, Object> startResponse = responseSchema("/charging/v21/operators/{operatorID}/authorize/start");
        Map<String, Object> statusCode = map(map(startResponse.get("properties")).get("StatusCode"));
        Map<String, Object> code = map(map(statusCode.get("properties")).get("Code"));
        String table = (String) code.get("description");

        Map<String, String> rows = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("\\|\\s*(\\d{3})\\s*\\|\\s*([^|]+?)\\s*\\|").matcher(table);
        while (matcher.find()) {
            rows.put(matcher.group(1), normalise(matcher.group(2)));
        }
        Map<String, String> ours = new LinkedHashMap<>();
        Arrays.stream(OicpStatus.values()).forEach(s -> ours.put(s.code(), normalise(s.description())));
        assertEquals(rows.keySet(), ours.keySet(), "codes differ");
        assertEquals(rows, ours, "descriptions differ");
    }

    private static String normalise(String text) {
        String t = text.replace('–', '-').replaceAll("\\s+", " ").trim();
        while (t.endsWith(".")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.replace(" / ", "/").replace("/ ", "/").toLowerCase(java.util.Locale.ROOT);
    }
}
