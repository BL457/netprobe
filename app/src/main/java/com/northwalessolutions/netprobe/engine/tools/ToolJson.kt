package com.northwalessolutions.netprobe.engine.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.Inet4Address

/**
 * tool_runs.optionsJson (spec 7.1): tls and serverName when the run has a target, then the tool's recorded inputs by name. The implicit
 * ip, port and tls inputs are not repeated (the run row and the target carry them), secrets are never written, a wordlist is written as
 * <name>Id, <name>Name and <name>Entries (so a dir-brute run keeps the field names history and export read), a list as an array.
 */
object ToolOptionsJson {
    fun encode(target: ToolTarget?, descriptor: ToolDescriptor, inputs: ToolInputs): String = buildJsonObject {
        if (target != null) {
            put("tls", target.tls)
            put("serverName", target.serverName?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        for ((name, value) in inputs.withoutSecrets(descriptor).values) {
            if (name == ToolInputs.IP || name == ToolInputs.PORT || name == ToolInputs.TLS) continue
            when (value) {
                null -> Unit
                is WordlistRef -> { put("${name}Id", value.id); put("${name}Name", value.name); put("${name}Entries", value.entries) }
                else -> put(name, element(value))
            }
        }
    }.toString()

    private fun element(value: Any): JsonElement = when (value) {
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Inet4Address -> JsonPrimitive(value.hostAddress)
        is List<*> -> JsonArray(value.filterNotNull().map { element(it) })
        else -> JsonPrimitive(value.toString())
    }
    /** [json] with [key] set to the string [value]; a malformed [json] is returned unchanged. */
    fun withOption(json: String, key: String, value: String): String = withOption(json, key, JsonPrimitive(value))
    /** [json] with [key] set to the number [value], the same JSON type [encode] writes for counts (wordlistEntries). */
    fun withOption(json: String, key: String, value: Int): String = withOption(json, key, JsonPrimitive(value))
    private fun withOption(json: String, key: String, value: JsonPrimitive): String =
        runCatching { JsonObject(Json.parseToJsonElement(json).jsonObject + (key to value)).toString() }.getOrElse { json }
}

/** tool_findings.extra (spec 7.1): url when present, then the per-kind fields; null when both are empty. */
object FindingExtraJson {
    fun encode(f: Finding): String? {
        if (f.url == null && f.extra.isEmpty()) return null
        return buildJsonObject { f.url?.let { put("url", it) }; f.extra.forEach { (k, v) -> put(k, v) } }.toString()
    }
}
