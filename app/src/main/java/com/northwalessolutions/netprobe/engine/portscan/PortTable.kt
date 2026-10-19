package com.northwalessolutions.netprobe.engine.portscan

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class PortEntry(val port: Int, val proto: String = "tcp", val name: String)

class PortTable(private val entries: Map<Int, String>) {
    fun nameFor(port: Int): String = entries[port] ?: "Unknown"
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(text: String): PortTable = runCatching {
            PortTable(json.decodeFromString<List<PortEntry>>(text).filter { it.proto == "tcp" }.associate { it.port to it.name })
        }.getOrElse { PortTable(emptyMap()) }
    }
}
