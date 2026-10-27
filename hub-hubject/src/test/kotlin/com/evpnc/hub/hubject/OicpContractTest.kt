package com.evpnc.hub.hubject

import com.evpnc.hub.core.FakeTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames

/**
 * Characterization of the legacy surface: the pinned OICP 2.3 EMP contract. These tests pin what we claim to implement
 * against the vendor file itself, so a contract bump (fetch script `--update-pins`) that moves a path, drops a field or adds a
 * status code fails here, in review, and not in the Hubject QA protocol.
 */
class OicpContractTest {
    @Suppress("UNCHECKED_CAST")
    private fun operation(path: String): Map<String, Any?> {
        val paths = PinnedContracts.oicpEmp["paths"] as Map<String, Any?>
        val item = paths[path] as? Map<String, Any?> ?: error("pinned contract has no path $path")
        return item["post"] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun requestSchema(path: String): Map<String, Any?> {
        val body = operation(path)["requestBody"] as Map<String, Any?>
        return ((body["content"] as Map<String, Any?>)["application/json"] as Map<String, Any?>)["schema"] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun responseSchema(path: String): Map<String, Any?> {
        val responses = operation(path)["responses"] as Map<String, Any?>
        val ok = responses["200"] as Map<String, Any?>
        return ((ok["content"] as Map<String, Any?>)["application/json"] as Map<String, Any?>)["schema"] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun properties(schema: Map<String, Any?>) = (schema["properties"] as Map<String, Any?>).keys

    @Suppress("UNCHECKED_CAST")
    private fun required(schema: Map<String, Any?>) = (schema["required"] as? List<String>).orEmpty().toSet()

    /** Every JSON name we model exists in the contract, and every required contract field is modelled. */
    private fun assertFieldsMatch(path: String, schema: Map<String, Any?>, serializer: KSerializer<*>) {
        val modelled = serializer.descriptor.elementNames.toSet()
        val contract = properties(schema)
        assertTrue(contract.containsAll(modelled), "$path: modelled but not in the contract: ${modelled - contract}")
        assertTrue(modelled.containsAll(required(schema)), "$path: required by the contract but not modelled: ${required(schema) - modelled}")
    }

    @Test
    fun `authorize start request and response match the pinned contract`() {
        val path = "/charging/v21/operators/{operatorID}/authorize/start"
        assertFieldsMatch("$path request", requestSchema(path), AuthorizeStartRequest.serializer())
        assertFieldsMatch("$path response", responseSchema(path), AuthorizeStartResponse.serializer())
    }

    @Test
    fun `remote start and stop requests match the pinned contract`() {
        val start = "/charging/v21/providers/{providerID}/authorize-remote/start"
        val stop = "/charging/v21/providers/{externalID}/authorize-remote/stop"
        assertFieldsMatch(start, requestSchema(start), AuthorizeRemoteStartRequest.serializer())
        assertFieldsMatch(stop, requestSchema(stop), AuthorizeRemoteStopRequest.serializer())
        assertFieldsMatch("$start response", responseSchema(start), OicpAcknowledgement.serializer())
    }

    @Test
    fun `charge detail record matches the pinned contract`() {
        val path = "/cdrmgmt/v22/operators/{operatorID}/charge-detail-record"
        assertFieldsMatch(path, requestSchema(path), OicpChargeDetailRecord.serializer())
    }

    @Test
    fun `the remote stop path parameter is named externalID in the contract and filled with the ProviderID`() {
        // Surprise recorded in docs/refactoring-report.md: the pinned file says externalID, the leading guide says ProviderID.
        operation("/charging/v21/providers/{externalID}/authorize-remote/stop")
        val transport = FakeTransport().apply { answers(200, """{"Result":true,"StatusCode":{"Code":"000"}}""") }
        HubjectRemoteClient("TRXYZ", transport, com.evpnc.hub.core.resilience.Throttler(emptyMap(), newClock()))
            .stop(com.evpnc.hub.core.RemoteStop(com.evpnc.hub.core.Hub.HUBJECT, "b2688855-7f00-0002-6d8e-48d883f6abb6", "TR*ZZZ*E0001*1"))
        assertEquals("/charging/v21/providers/TRXYZ/authorize-remote/stop", transport.sent.single().path)
    }

    @Test
    fun `our status code catalogue equals the contract's list`() {
        @Suppress("UNCHECKED_CAST")
        val code = (((responseSchema("/charging/v21/operators/{operatorID}/authorize/start")["properties"] as Map<String, Any?>)["StatusCode"]
            as Map<String, Any?>)["properties"] as Map<String, Any?>)["Code"] as Map<String, Any?>
        val table = code["description"] as String
        val rows = Regex("""\|\s*(\d{3})\s*\|\s*([^|]+?)\s*\|""").findAll(table).associate { it.groupValues[1] to normalise(it.groupValues[2]) }
        val ours = OicpStatus.entries.associate { it.code to normalise(it.description) }
        assertEquals(rows.keys, ours.keys, "codes differ")
        assertEquals(rows, ours, "descriptions differ")
    }

    private fun normalise(text: String) =
        text.replace('–', '-').replace(Regex("\\s+"), " ").trim().trimEnd('.').replace(" / ", "/").replace("/ ", "/").lowercase()
}
