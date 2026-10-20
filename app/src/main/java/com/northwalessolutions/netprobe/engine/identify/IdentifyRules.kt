package com.northwalessolutions.netprobe.engine.identify

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Rule(
    val id: String, val field: String, val pattern: String? = null, val ports: List<Int>? = null, val anyPort: List<Int>? = null,
    val vendor: String? = null, val model: String? = null, val os: String? = null, val type: String? = null, val weight: Int, val sample: String? = null,
    /** Port evidence from these ports only; discovery-time evidence is not considered (spec 3.2). */
    val onPorts: List<Int>? = null,
    /** What the box runs, shown as "Runs <software>"; collected from every matching rule, never a weighted slot (spec 3.6). */
    val software: String? = null,
)
object IdentifyRules {
    fun load(json: String): List<Rule> = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<List<Rule>>(json) }.getOrElse { emptyList() }
}
