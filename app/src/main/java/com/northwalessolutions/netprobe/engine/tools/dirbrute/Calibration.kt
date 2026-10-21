package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.tools.RunControl

data class Calibration(val baselines: Map<String, Baseline>, val forceGetOnly: Boolean, val anyResponse: Boolean)

/**
 * spec 5.4.1: groups "" (bare) and one per extension; per group, GET 5 random paths; HEAD / and GET /, and HEAD the first bare random
 * path. A HEAD/GET status disagreement forces GET_ONLY. A failed sample is retried once. No response at all is a failed calibration,
 * and when HEAD / and GET / both fail after their retries the host is taken as dead at once: at most 4 timeouts (ruling R9).
 *
 * Each group also has a dot twin ([DirBrutePaths.dotGroup]) sampled as `/.<random>[.<ext>]`: servers such as DSM's nginx answer
 * every path with a dot segment by a fixed 403 page, which plain random paths never see, so without the twin those 403s leak
 * through as findings and trip the runaway check (Phase 3B QA F2). At most 5 x 2 x (1 + 8) = 90 GETs, each behind its own gate.
 */
object Calibrator {
    const val SAMPLES_PER_GROUP = 5
    private const val RANDOM_LEN = 12

    private suspend fun request(exchange: Exchange, control: RunControl, method: String, path: String): HttpResult {
        control.gate()
        val first = exchange.request(method, path)
        if (first is HttpResult.Ok) return first
        control.gate()
        return exchange.request(method, path)
    }

    suspend fun calibrate(groups: List<String>, exchange: Exchange, control: RunControl, random: () -> String): Calibration {
        var forceGetOnly = false
        val headRoot = request(exchange, control, "HEAD", "/"); val getRoot = request(exchange, control, "GET", "/")
        if (headRoot !is HttpResult.Ok && getRoot !is HttpResult.Ok) return Calibration(emptyMap(), forceGetOnly = false, anyResponse = false)
        if (headRoot is HttpResult.Ok && getRoot is HttpResult.Ok && headRoot.response.status != getRoot.response.status) forceGetOnly = true
        val baselines = HashMap<String, Baseline>()
        for (group in groups.flatMap { listOf(it, dotGroup(it)) }) {
            val dot = isDotGroup(group); val ext = extOf(group)
            val sizes = HashMap<Int, MutableList<Long?>>(); val locations = HashMap<Int, MutableSet<String>>()
            for (i in 0 until SAMPLES_PER_GROUP) {
                val path = "/" + (if (dot) "." else "") + random().take(RANDOM_LEN) + if (ext.isEmpty()) "" else ".$ext"
                val r = request(exchange, control, "GET", path)
                if (group.isEmpty() && i == 0) {
                    val head = request(exchange, control, "HEAD", path)
                    if (head is HttpResult.Ok && r is HttpResult.Ok && head.response.status != r.response.status) forceGetOnly = true
                }
                if (r !is HttpResult.Ok) continue
                val status = r.response.status
                sizes.getOrPut(status) { mutableListOf() } += NotFoundFilter.normalisedSize(r.response, path)
                if (status in 300..399) NotFoundFilter.normaliseLocation(r.response.header("Location"), path)?.let { locations.getOrPut(status) { mutableSetOf() } += it }
            }
            if (sizes.isNotEmpty()) baselines[group] = Baseline(sizes.mapValues { NotFoundFilter.bandOf(it.value) }, locations.mapValues { it.value.toSet() })
        }
        return Calibration(baselines, forceGetOnly, anyResponse = true)   // the root answered
    }
}
