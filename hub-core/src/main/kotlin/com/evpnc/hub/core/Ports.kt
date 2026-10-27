package com.evpnc.hub.core

/** Policy hook: price threshold, operator allow and block lists, consent. Implemented by the policy engine. */
fun interface AuthorizationPolicy {
    fun decide(request: AuthorizeRequest): AuthorizeDecision
}

/** Where received CDRs go (payments, invoicing). Implemented by the platform, never by an adapter. */
fun interface CdrSink {
    fun accept(cdr: HubCdr)
}

/** Commands the platform sends to a hub on behalf of a driver. Implemented by an adapter. */
interface RemoteSessionPort {
    fun start(command: RemoteStart): RemoteResult

    fun stop(command: RemoteStop): RemoteResult
}

/** Minimal HTTP seam. Adapters build requests as data; a transport (mTLS client, test fake) executes them. */
data class HttpRequestSpec(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class HttpResponseSpec(val status: Int, val body: String? = null, val headers: Map<String, String> = emptyMap())

class TransportTimeout(message: String) : RuntimeException(message)

fun interface HubTransport {
    /** @throws TransportTimeout when the hub did not answer in time. */
    fun send(request: HttpRequestSpec): HttpResponseSpec
}
