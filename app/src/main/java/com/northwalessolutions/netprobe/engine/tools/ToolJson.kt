package com.northwalessolutions.netprobe.engine.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** tool_runs.optionsJson (spec 7.1): always tls and serverName, plus the brute-force fields for a DirBrute run. */
object ToolOptionsJson {
    fun encode(target: ToolTarget, options: ToolOptions): String = buildJsonObject {
        put("tls", target.tls)
        put("serverName", target.serverName?.let { JsonPrimitive(it) } ?: JsonNull)
        if (options is ToolOptions.DirBrute) {
            val o = options.options
            put("wordlistId", o.wordlistId); put("wordlistName", options.wordlistName); put("wordlistEntries", options.wordlistEntries)
            put("extensions", JsonArray(o.extensions.map { JsonPrimitive(it) }))
            put("method", o.method.name); put("methodUsed", o.method.name)   // the chosen method; the calibration switch rewrites methodUsed (spec 5.2, G8)
            put("concurrency", o.concurrency); put("timeoutS", o.timeoutS)
            put("statusCodes", JsonArray(o.statusCodes.map { JsonPrimitive(it) })); put("baseline", o.baseline)
        }
    }.toString()
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
