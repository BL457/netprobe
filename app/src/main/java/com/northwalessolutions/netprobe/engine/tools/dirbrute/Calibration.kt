package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.tools.RunControl

data class Calibration(val baselines: Map<String, Baseline>, val forceGetOnly: Boolean, val anyResponse: Boolean)

/**
 * spec 5.4.1: groups "" (bare) and one per extension; per group, GET 5 random paths; HEAD / and GET /, and HEAD the first bare random
 * path. A HEAD/GET status disagreement forces GET_ONLY. A failed sample is retried once. No response at all is a failed calibration.
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
        var anyResponse = false; var forceGetOnly = false
        val headRoot = request(exchange, control, "HEAD", "/"); val getRoot = request(exchange, control, "GET", "/")
        if (headRoot is HttpResult.Ok || getRoot is HttpResult.Ok) anyResponse = true
        if (headRoot is HttpResult.Ok && getRoot is HttpResult.Ok && headRoot.response.status != getRoot.response.status) forceGetOnly = true
        val baselines = HashMap<String, Baseline>()
        for (group in groups) {
            val sizes = HashMap<Int, MutableList<Long?>>(); val locations = HashMap<Int, MutableSet<String>>()
            for (i in 0 until SAMPLES_PER_GROUP) {
                val path = "/" + random().take(RANDOM_LEN) + if (group.isEmpty()) "" else ".$group"
                val r = request(exchange, control, "GET", path)
                if (group.isEmpty() && i == 0) {
                    val head = request(exchange, control, "HEAD", path)
                    if (head is HttpResult.Ok && r is HttpResult.Ok && head.response.status != r.response.status) forceGetOnly = true
                }
                if (r !is HttpResult.Ok) continue
                anyResponse = true
                val status = r.response.status
                sizes.getOrPut(status) { mutableListOf() } += NotFoundFilter.normalisedSize(r.response, path)
                if (status in 300..399) NotFoundFilter.normaliseLocation(r.response.header("Location"), path)?.let { locations.getOrPut(status) { mutableSetOf() } += it }
            }
            if (sizes.isNotEmpty()) baselines[group] = Baseline(sizes.mapValues { NotFoundFilter.bandOf(it.value) }, locations.mapValues { it.value.toSet() })
        }
        return Calibration(baselines, forceGetOnly, anyResponse)
    }
}
