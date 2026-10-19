package com.northwalessolutions.netprobe.engine.vulns

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

data class Vuln(val cveId: String, val cvss: Double?, val severity: String, val published: String, val summary: String, val url: String)
fun interface HttpGet { suspend fun get(url: String, headers: Map<String, String>): Pair<Int, String> }

object NvdParser {
    fun parse(json: String): Pair<List<Vuln>, Int>? = try {
        val root = Json.parseToJsonElement(json).jsonObject
        val total = root["totalResults"]?.jsonPrimitive?.intOrNull ?: 0
        val list = root["vulnerabilities"]?.jsonArray ?: return null
        val vulns = list.mapNotNull { item ->
            val cve = item.jsonObject["cve"]?.jsonObject ?: return@mapNotNull null
            val id = cve["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val metrics = cve["metrics"]?.jsonObject
            fun first(key: String) = metrics?.get(key)?.jsonArray?.firstOrNull()?.jsonObject
            // Pick one metric (V31, else V30, else V2) and read score and severity from it, so they never mix across versions.
            val v3 = first("cvssMetricV31") ?: first("cvssMetricV30"); val m = v3 ?: first("cvssMetricV2")
            val data = m?.get("cvssData")?.jsonObject
            val score = data?.get("baseScore")?.jsonPrimitive?.doubleOrNull
            val severity = (if (v3 != null) data?.get("baseSeverity") else m?.get("baseSeverity"))?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
            val summary = cve["descriptions"]?.jsonArray?.firstOrNull { it.jsonObject["lang"]?.jsonPrimitive?.content == "en" }?.jsonObject?.get("value")?.jsonPrimitive?.content ?: ""
            Vuln(id, score, severity, cve["published"]?.jsonPrimitive?.contentOrNull ?: "", summary, "https://nvd.nist.gov/vuln/detail/$id")
        }.sortedByDescending { it.cvss ?: -1.0 }
        vulns to total
    } catch (_: Exception) { null }
}

class UrlHttpGet(private val timeoutMs: Int = 15_000) : HttpGet {
    override suspend fun get(url: String, headers: Map<String, String>): Pair<Int, String> = runInterruptible(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false   // never let the apiKey header follow a redirect
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.setRequestProperty("User-Agent", "NetProbe/0.1")
            val code = c.responseCode
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } finally { c.disconnect() }
    }
}
