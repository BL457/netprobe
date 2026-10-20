package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.engine.common.HttpResponse
import kotlin.math.max

/** One status's size band in one group (spec 5.4.2); [unknown] when a sample had no size, which forms its own match. */
data class SizeBand(val min: Long, val max: Long, val known: Boolean, val unknown: Boolean) {
    fun contains(size: Long?): Boolean = if (size == null) unknown else known && size in min..max
}
/** A group's baselines: a band per status, and for 3xx the set of normalised Locations. */
data class Baseline(val bands: Map<Int, SizeBand>, val locations: Map<Int, Set<String>>)

object NotFoundFilter {
    const val PAD_MIN = 32L
    const val PATH_TOKEN = "<PATH>"
    /**
     * The response size (Content-Length when known, else the body bytes) minus (occurrences of the requested path in the body x its
     * byte length); null when truncated with no Content-Length. Content-Length wins so a body cut at the 64 KB cap still bands at its
     * real size (Task 5 review: bodyTruncated means only that the cap was hit).
     */
    fun normalisedSize(r: HttpResponse, requestedPath: String): Long? {
        if (r.bodyTruncated && r.contentLength == null) return null
        val pat = requestedPath.toByteArray(Charsets.UTF_8)
        return (r.contentLength ?: r.body.size.toLong()) - occurrences(r.body, pat).toLong() * pat.size
    }
    private fun occurrences(body: ByteArray, pat: ByteArray): Int {
        if (pat.isEmpty() || pat.size > body.size) return 0
        var count = 0; var i = 0
        while (i <= body.size - pat.size) {
            var match = true
            for (j in pat.indices) if (body[i + j] != pat[j]) { match = false; break }
            if (match) { count++; i += pat.size } else i++
        }
        return count
    }
    fun normaliseLocation(location: String?, requestedPath: String): String? = location?.replace(requestedPath, PATH_TOKEN)
    /** [min - pad, max + pad] with pad = max(32, max - min) over the known sizes of one status's samples. */
    fun bandOf(sizes: List<Long?>): SizeBand {
        val known = sizes.filterNotNull()
        if (known.isEmpty()) return SizeBand(0, 0, known = false, unknown = true)
        val lo = known.min(); val hi = known.max(); val pad = max(PAD_MIN, hi - lo)
        return SizeBand(lo - pad, hi + pad, known = true, unknown = sizes.any { it == null })
    }
    /** Hidden when the group has a band for this status containing the size, and for 3xx the normalised Location is in its set. */
    fun isHidden(baseline: Baseline, status: Int, size: Long?, normalisedLocation: String?): Boolean {
        val band = baseline.bands[status] ?: return false
        if (!band.contains(size)) return false
        if (status !in 300..399) return true
        val set = baseline.locations[status] ?: return false
        return normalisedLocation != null && normalisedLocation in set
    }
}
