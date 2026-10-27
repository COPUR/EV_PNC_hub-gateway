package com.evpnc.hub.hubject

import com.evpnc.hub.core.AuthorizationPolicy
import com.evpnc.hub.core.AuthorizeDecision
import com.evpnc.hub.core.MutableClock
import java.io.File
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

object Policies {
    val allow = AuthorizationPolicy { AuthorizeDecision.Allowed() }
}

fun newClock() = MutableClock()

/** The pinned contracts, checked out by tools/fetch-contracts.sh (CI) or pointed at with CONTRACTS_DIR. */
object PinnedContracts {
    private val root: File by lazy {
        val dir = File(System.getenv("CONTRACTS_DIR") ?: ".contracts")
        check(File(dir, "_sources/hubject-oicp-2.3-emp.openapi.yaml").exists()) {
            "pinned contracts not found at ${dir.absolutePath}. Run tools/fetch-contracts.sh or set CONTRACTS_DIR to a checkout of EV_PNC_hub-contracts."
        }
        dir
    }

    @Suppress("UNCHECKED_CAST")
    val oicpEmp: Map<String, Any?> by lazy {
        val options = LoaderOptions().apply { codePointLimit = 64 * 1024 * 1024 }
        Yaml(options).load<Any?>(File(root, "_sources/hubject-oicp-2.3-emp.openapi.yaml").readText()) as Map<String, Any?>
    }
}
