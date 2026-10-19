package com.northwalessolutions.netprobe.engine.vulns

import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class CpeEntry(val product: String, val cpe: String)
object Cpe {
    fun load(json: String): Map<String, String> = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<List<CpeEntry>>(json).associate { it.product to it.cpe } }.getOrDefault(emptyMap())
    fun forService(map: Map<String, String>, s: ServiceInfo): String? {
        val product = s.product ?: return null; val version = s.version ?: return null
        if (!SAFE_VERSION.matches(version)) return null
        val tpl = map.entries.firstOrNull { it.key.equals(product, ignoreCase = true) }?.value ?: return null
        val v = version.lowercase()
        // NVD records OpenSSH 9.6p1 as version 9.6, update p1. Templates end "{v}:*"; the update takes the place of that first wildcard.
        val m = if (product.equals("OpenSSH", ignoreCase = true)) OPENSSH_PORTABLE.matchEntire(v) else null
        return if (m != null) tpl.replace("{v}:*", "${m.groupValues[1]}:p${m.groupValues[2]}") else tpl.replace("{v}", v)
    }
    private val SAFE_VERSION = Regex("^[A-Za-z0-9._~+-]+$")
    private val OPENSSH_PORTABLE = Regex("^(\\d+\\.\\d+)p(\\d+)$")
}
