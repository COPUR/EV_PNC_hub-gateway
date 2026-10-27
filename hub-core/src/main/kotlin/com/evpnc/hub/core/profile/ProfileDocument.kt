package com.evpnc.hub.core.profile

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

enum class Confidence { FACT, INFERENCE, UNKNOWN }

/**
 * A hub profile from EV_PNC_hub-contracts, read as data (ADR-0001). Adapters read rules through typed views built on
 * this class; they do not hard-code hub behaviour. Paths are dotted: `timeouts.authorize_start_response_s.value`.
 */
class ProfileDocument(val name: String, private val root: Map<String, Any?>) {
    fun node(path: String): Any? {
        var current: Any? = root
        for (part in path.split('.')) {
            current = when (current) {
                is Map<*, *> -> current[part]
                is List<*> -> part.toIntOrNull()?.let { current.getOrNull(it) }
                else -> null
            }
            if (current == null) return null
        }
        return current
    }

    fun has(path: String): Boolean = node(path) != null

    fun string(path: String): String = optionalString(path) ?: missing(path)

    fun optionalString(path: String): String? = node(path)?.toString()

    fun int(path: String): Int = (node(path) as? Number)?.toInt() ?: missing(path)

    fun bool(path: String): Boolean = node(path) as? Boolean ?: missing(path)

    fun stringList(path: String): List<String> =
        (node(path) as? List<*>)?.map { it.toString() } ?: missing(path)

    @Suppress("UNCHECKED_CAST")
    fun map(path: String): Map<String, Any?> = node(path) as? Map<String, Any?> ?: missing(path)

    /** The `confidence` tag on the node itself, or on the nearest ancestor that has one. */
    fun confidence(path: String): Confidence? {
        val parts = path.split('.')
        for (length in parts.size downTo 0) {
            val parent = if (length == 0) root else node(parts.take(length).joinToString("."))
            val tag = (parent as? Map<*, *>)?.get("confidence") as? String ?: continue
            return parseConfidence(tag)
        }
        return null
    }

    /** Paths of every entry tagged `unknown`: what a mock must not pretend to know and a real hub must still be asked. */
    fun unknownPaths(): List<String> = buildList { collectUnknown(root, "", this) }

    private fun collectUnknown(node: Any?, path: String, out: MutableList<String>) {
        when (node) {
            is Map<*, *> -> {
                if (parseConfidence(node["confidence"] as? String) == Confidence.UNKNOWN) out.add(path.ifEmpty { "<root>" })
                node.forEach { (key, value) ->
                    if (key != "confidence") collectUnknown(value, if (path.isEmpty()) "$key" else "$path.$key", out)
                }
            }
            is List<*> -> node.forEachIndexed { i, value -> collectUnknown(value, "$path.$i", out) }
        }
    }

    private fun missing(path: String): Nothing = throw IllegalStateException("profile '$name' has no value at '$path'")

    private fun parseConfidence(text: String?): Confidence? = when {
        text == null -> null
        text.startsWith("fact") -> Confidence.FACT
        text.startsWith("inference") -> Confidence.INFERENCE
        text.startsWith("unknown") -> Confidence.UNKNOWN
        else -> null
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parse(name: String, yaml: String): ProfileDocument {
            val loaded = Yaml(LoaderOptions().apply { codePointLimit = 4 * 1024 * 1024 }).load<Any?>(yaml)
            val root = loaded as? Map<String, Any?> ?: throw IllegalArgumentException("profile '$name' is not a YAML mapping")
            return ProfileDocument(name, root)
        }
    }
}

object Profiles {
    /** Profiles are vendored from EV_PNC_hub-contracts under `profiles/` on the classpath. `tools/sync-contracts.sh` keeps them equal. */
    fun fromClasspath(file: String): ProfileDocument {
        val stream = Profiles::class.java.classLoader.getResourceAsStream("profiles/$file")
            ?: throw IllegalStateException("profile 'profiles/$file' not on the classpath")
        return stream.use { parse(file, it.readBytes().decodeToString()) }
    }

    private fun parse(name: String, text: String) = ProfileDocument.parse(name, text)
}
