package com.northwalessolutions.netprobe.ui.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** tool_runs.optionsJson read back (spec 7.1); every field defaults, and a malformed value decodes to defaults. */
@Serializable
data class ToolRunOptions(
    val tls: Boolean = false, val serverName: String? = null,
    val wordlistId: String? = null, val wordlistName: String? = null, val wordlistEntries: Int? = null,
    val extensions: List<String> = emptyList(), val method: String? = null, val methodUsed: String? = null,
    val concurrency: Int? = null, val timeoutS: Int? = null, val statusCodes: List<Int> = emptyList(), val baseline: Boolean? = null,
) {
    val isDirBrute: Boolean get() = wordlistId != null
    /** The export Options line (spec 7.5), brute-force runs only. */
    fun optionsLine(): String? {
        val id = wordlistId ?: return null
        val name = wordlistName?.takeIf { it.isNotEmpty() } ?: id.substringAfter(':')
        val usedGetOnly = methodUsed == GET_ONLY
        return ToolCopy.optionsLine(name, wordlistEntries ?: 0, extensions, getOnly = usedGetOnly || method == GET_ONLY,
            switchedToGetOnly = usedGetOnly && method != GET_ONLY, concurrency = concurrency ?: DEFAULT_CONCURRENCY, timeoutS = timeoutS ?: DEFAULT_TIMEOUT_S,
            codes = statusCodes, filter = baseline ?: true)
    }
    companion object {
        private const val GET_ONLY = "GET_ONLY"
        private const val DEFAULT_CONCURRENCY = 8
        private const val DEFAULT_TIMEOUT_S = 3
        private val decoder = Json { ignoreUnknownKeys = true; explicitNulls = false }
        fun parse(text: String): ToolRunOptions {
            return runCatching { decoder.decodeFromString<ToolRunOptions>(text) }.getOrElse { ToolRunOptions() }
        }
    }
}

/** tool_findings.extra read back (spec 7.1): string fields only; anything malformed is empty. */
object FindingExtra {
    fun decode(text: String?): Map<String, String> {
        if (text == null) return emptyMap()
        val obj: JsonObject = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
        return obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }.toMap()
    }
}
