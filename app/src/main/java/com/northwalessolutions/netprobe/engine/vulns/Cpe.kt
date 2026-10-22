package com.northwalessolutions.netprobe.engine.vulns

import com.northwalessolutions.netprobe.engine.serviceid.BannerParsers
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class CpeEntry(val product: String, val cpe: String)
object Cpe {
    fun load(json: String): Map<String, String> = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<List<CpeEntry>>(json).associate { it.product to it.cpe } }.getOrDefault(emptyMap())
    /**
     * The CPE of a service, built only from a template of [map] (spec 7): the key is the finding's [ServiceInfo.cpe] product when a match or
     * rule named one, else its product. A name that is not a key of the map gives nothing, so a free CPE string in the data can never
     * reach a query. The version must pass SAFE_VERSION first.
     */
    fun forService(map: Map<String, String>, s: ServiceInfo): String? {
        // OpenSSH for Windows is OpenSSH: NVD records it under the openbsd:openssh CPE.
        val named = s.cpe ?: s.product
        val product = named?.let { if (it.equals(BannerParsers.OPENSSH_WINDOWS, ignoreCase = true)) OPENSSH else it } ?: return null
        val version = s.version ?: return null
        if (!SAFE_VERSION.matches(version)) return null
        val tpl = map.entries.firstOrNull { it.key.equals(product, ignoreCase = true) }?.value ?: return null
        val v = version.lowercase()
        // NVD records OpenSSH 9.6p1 as version 9.6, update p1. Templates end "{v}:*"; the update takes the place of that first wildcard.
        val m = if (product.equals(OPENSSH, ignoreCase = true)) OPENSSH_PORTABLE.matchEntire(v) else null
        return if (m != null) tpl.replace("{v}:*", "${m.groupValues[1]}:p${m.groupValues[2]}") else tpl.replace("{v}", v)
    }
    private const val OPENSSH = "OpenSSH"
    private val SAFE_VERSION = Regex("^[A-Za-z0-9._~+-]+$")
    private val OPENSSH_PORTABLE = Regex("^(\\d+\\.\\d+)p(\\d+)$")
}
