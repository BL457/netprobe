package com.northwalessolutions.netprobe.engine.vulns

import com.northwalessolutions.netprobe.engine.identify.DeviceIdentity
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.URLEncoder

sealed class VulnQuery(val text: String) {
    /** [fallbackKeyword] is searched instead when NVD has no configuration for the CPE and returns zero results. */
    class ByCpe(cpe: String, val fallbackKeyword: String? = null) : VulnQuery(cpe)
    class ByKeyword(kw: String) : VulnQuery(kw)
}
sealed class VulnResult {
    /** [queryText] is the query that actually produced this result (the keyword when the CPE fallback ran). */
    data class Ok(val vulns: List<Vuln>, val total: Int, val fromCache: Boolean, val fetchedAt: Long, val queryText: String = "") : VulnResult()
    data class Failed(val reason: String, val cached: Ok?) : VulnResult()
}
interface VulnCache {
    suspend fun get(query: String, maxAgeMs: Long): Pair<String, Long>?
    suspend fun getAny(query: String): Pair<String, Long>?
    suspend fun put(query: String, json: String)
}

class VulnLookup(
    private val http: HttpGet, private val cache: VulnCache, private val apiKey: () -> String?, private val cpeMap: Map<String, String>,
    private val limiterNoKey: RateLimiter = RateLimiter(NO_KEY_PERMITS, WINDOW_MS), private val limiterKey: RateLimiter = RateLimiter(KEY_PERMITS, WINDOW_MS),
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    companion object {
        const val BASE = "https://services.nvd.nist.gov/rest/json/cves/2.0?resultsPerPage=50&"
        const val NO_KEY_PERMITS = 5
        const val KEY_PERMITS = 50
        const val WINDOW_MS = 30_000L
        const val DEFAULT_TTL_MS = 24 * 3600_000L
    }

    fun queryForService(s: ServiceInfo): VulnQuery? {
        Cpe.forService(cpeMap, s)?.let { return VulnQuery.ByCpe(it, fallbackKeyword = listOfNotNull(s.product, s.version).joinToString(" ")) }
        val p = s.product ?: return null
        return VulnQuery.ByKeyword(listOfNotNull(p, s.version).joinToString(" "))
    }
    fun queryForDevice(d: DeviceIdentity): VulnQuery? {
        val kw = listOfNotNull(d.vendor, d.model).joinToString(" ").trim()
        return if (kw.isEmpty()) null else VulnQuery.ByKeyword(kw)
    }

    suspend fun run(q: VulnQuery): VulnResult {
        val first = runOnce(q)
        // A CPE that NVD has no configuration for returns zero results; retry once as a keyword search and report that query text.
        if (q is VulnQuery.ByCpe && q.fallbackKeyword != null && first is VulnResult.Ok && first.total == 0) return runOnce(VulnQuery.ByKeyword(q.fallbackKeyword))
        return first
    }

    private suspend fun runOnce(q: VulnQuery): VulnResult {
        val key = "${q::class.simpleName}:${q.text}"
        cache.get(key, ttlMs)?.let { (json, at) -> NvdParser.parse(json)?.let { (v, t) -> return VulnResult.Ok(v, t, fromCache = true, fetchedAt = at, queryText = q.text) } }
        val stale = cache.getAny(key)?.let { (json, at) -> NvdParser.parse(json)?.let { (v, t) -> VulnResult.Ok(v, t, true, at, q.text) } }
        val param = when (q) { is VulnQuery.ByCpe -> "cpeName="; is VulnQuery.ByKeyword -> "keywordSearch=" } + URLEncoder.encode(q.text, "UTF-8")
        val k = apiKey()?.takeIf { it.isNotBlank() }
        (if (k != null) limiterKey else limiterNoKey).acquire()
        val (code, body) = try { http.get(BASE + param, if (k != null) mapOf("apiKey" to k) else emptyMap()) }
            catch (e: CancellationException) { throw e }
            catch (e: IOException) { return VulnResult.Failed("Offline", stale) }
            catch (e: Exception) { return VulnResult.Failed("Offline", stale) }
        return when {
            code == 403 || code == 429 -> VulnResult.Failed("NVD rate limit reached, try again in 30 seconds or add an API key in Settings", stale)
            code != 200 -> VulnResult.Failed("NVD returned HTTP $code", stale)
            else -> NvdParser.parse(body)?.let { (v, t) -> cache.put(key, body); VulnResult.Ok(v, t, false, System.currentTimeMillis(), q.text) }
                ?: VulnResult.Failed("NVD returned an unexpected response", stale)
        }
    }
}
