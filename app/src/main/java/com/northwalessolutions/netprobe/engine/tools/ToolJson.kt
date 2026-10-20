package com.northwalessolutions.netprobe.engine.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** tool_runs.optionsJson (spec 7.1): always tls and serverName; Task 10 adds the brute-force fields. */
object ToolOptionsJson {
    fun encode(target: ToolTarget, options: ToolOptions): String = buildJsonObject {
        put("tls", target.tls)
        put("serverName", target.serverName?.let { JsonPrimitive(it) } ?: JsonNull)
    }.toString()
    /** [json] with [key] set to [value]; a malformed [json] is returned unchanged. */
    fun withOption(json: String, key: String, value: String): String =
        runCatching { JsonObject(Json.parseToJsonElement(json).jsonObject + (key to JsonPrimitive(value))).toString() }.getOrElse { json }
}

/** tool_findings.extra (spec 7.1): url when present, then the per-kind fields; null when both are empty. */
object FindingExtraJson {
    fun encode(f: Finding): String? {
        if (f.url == null && f.extra.isEmpty()) return null
        return buildJsonObject { f.url?.let { put("url", it) }; f.extra.forEach { (k, v) -> put(k, v) } }.toString()
    }
}
