package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.HttpFailure
import com.northwalessolutions.netprobe.engine.common.HttpResponse
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.*
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

fun interface Exchange { suspend fun request(method: String, path: String): HttpResult }

/** A resolved wordlist: id, display name, entry count (for the path total) and a streaming collector (spec 5.6.3). */
data class LoadedWordlist(val id: String, val name: String, val count: Int, val stream: suspend (collect: suspend (String) -> Unit) -> Unit)

/**
 * Directory brute-force (spec 5). The wordlist streams through a channel to [DirBruteOptions.concurrency] workers. Every path first
 * takes an adaptive-limiter permit, and every request it sends (HEAD and GET each) first passes control.gate() (pause and rate ceiling). HEAD then GET with the 405/501 fallback, the calibrated
 * filter per group, one retry for Timeout and Reset, the 50-in-a-row abort, the 500 cap and the runaway check. Excused failures
 * (a pause began during the request) are retried after the resume and never counted.
 */
class DirBruteforceTool(
    private val exchangeFor: (target: ToolTarget, timeoutMs: Int) -> Exchange,
    private val resolve: suspend (wordlistId: String) -> LoadedWordlist?,
    private val random: () -> String = ::randomToken,
    private val clock: () -> Long = System::currentTimeMillis,
) : Tool {
    override val id = ToolId.DIR_BRUTEFORCE
    override val name = ToolCopy.DIR_BRUTEFORCE
    override fun applies(port: Int, service: ServiceInfo) = ToolApplicability.appliesTo(ToolId.DIR_BRUTEFORCE, port, service)

    private data class Item(val path: String, val group: String)
    private sealed interface Outcome { data class Hit(val r: HttpResponse) : Outcome; object Miss : Outcome; data class Failure(val f: HttpFailure) : Outcome }

    /** Shared run state; every field is touched by the workers, so all are atomic or guarded. */
    private class State(getOnly: Boolean) {
        @Volatile var getOnly = getOnly
        val done = AtomicInteger(0); val found = AtomicInteger(0); val errors = AtomicInteger(0)
        val consecutive = AtomicInteger(0); val headFallbacks = AtomicInteger(0)
        val samples = ArrayList<Pair<Int, Long?>>()
        @Volatile var end: ToolResult? = null
        @Synchronized fun endWith(r: ToolResult) { if (end == null) end = r }
        @Synchronized fun sample(status: Int, size: Long?) { samples += status to size }
        @Synchronized fun snapshot(): List<Pair<Int, Long?>> = samples.toList()
        /** [found] counts claimed slots, which can pass the cap by the few dropped claims (plan review G22). */
        fun foundCount(): Int = minOf(found.get(), MAX_FINDINGS)
    }

    override suspend fun run(target: ToolTarget, options: ToolOptions, control: RunControl, progress: ToolProgress): ToolResult {
        val o = ((options as? ToolOptions.DirBrute)?.options ?: DirBruteOptions()).coerced()
        val list = resolve(o.wordlistId) ?: return ToolResult(ToolRunStatus.FAILED, ToolCopy.failed(ToolCopy.WORDLIST_FALLBACK))
        val timeoutMs = o.timeoutS * MS_PER_S
        val exchange = exchangeFor(target, timeoutMs)
        val total = list.count * (1 + o.extensions.size)
        progress.update(0, total, 0, 0)

        var getOnly = o.method == DirMethod.GET_ONLY
        val calibration = if (o.baseline) Calibrator.calibrate(listOf("") + o.extensions, exchange, control, random) else null
        if (calibration != null) {
            if (!calibration.anyResponse) return ToolResult(ToolRunStatus.FAILED, ToolCopy.BRUTE_NO_ANSWER)
            if (calibration.forceGetOnly && !getOnly) { getOnly = true; progress.option(METHOD_USED, DirMethod.GET_ONLY.name) }
        }
        val state = State(getOnly)
        val limiter = AdaptiveLimiter(o.concurrency, clock, timeoutMs.toLong())
        val reportSet = o.statusCodes.toSet()

        coroutineScope {
            val channel = Channel<Item>(capacity = CHANNEL_CAPACITY)
            val producer = launch {
                try {
                    list.stream { w ->
                        channel.send(Item("/" + encodePath(w), ""))
                        for (ext in o.extensions) channel.send(Item("/" + encodePath("$w.$ext"), ext))
                    }
                } finally { channel.close() }
            }
            val workers = List(o.concurrency) {
                launch {
                    for (item in channel) {
                        if (state.end != null) break
                        handle(item, target, exchange, control, limiter, reportSet, calibration, state, progress, total)
                    }
                }
            }
            workers.joinAll()
            producer.cancel()
        }

        state.end?.let { return it }
        return ToolResult(ToolRunStatus.DONE, ToolCopy.bruteDone(state.foundCount(), state.done.get(), state.errors.get()))
    }

    private suspend fun handle(item: Item, target: ToolTarget, exchange: Exchange, control: RunControl, limiter: AdaptiveLimiter, reportSet: Set<Int>,
                               calibration: Calibration?, state: State, progress: ToolProgress, total: Int) {
        val outcome = attemptCounted(item.path, exchange, control, limiter, reportSet, state)
        val d = state.done.incrementAndGet()
        when (outcome) {
            is Outcome.Hit -> { state.consecutive.set(0); report(item, outcome.r, target, calibration, state, progress) }
            is Outcome.Miss -> state.consecutive.set(0)
            is Outcome.Failure -> {
                state.errors.incrementAndGet()
                if (state.consecutive.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES)
                    state.endWith(ToolResult(ToolRunStatus.FAILED, ToolCopy.bruteStoppedAnswering(d)))
            }
        }
        progress.update(d, total, state.foundCount(), state.errors.get())
        if (d == RUNAWAY_AFTER && runaway(state.snapshot())) state.endWith(ToolResult(ToolRunStatus.DONE, ToolCopy.BRUTE_RUNAWAY))
    }

    /**
     * One path to a counted outcome: excused failures retry after the resume; Timeout and Reset retry once after RETRY_DELAY_MS.
     * The gate is not taken here: [attempt] takes it before every request, so a HEAD then GET costs two rate tokens (plan review G3).
     * The epoch is read before the first gate, so a pause that starts while this path waits at the gate also excuses its failure:
     * at worst one uncounted extra try, never a counted error the Director caused.
     */
    private suspend fun attemptCounted(path: String, exchange: Exchange, control: RunControl, limiter: AdaptiveLimiter, reportSet: Set<Int>, state: State): Outcome {
        var retried = false
        while (true) {
            val epoch = control.pauseEpoch
            limiter.acquire()
            val outcome = try { attempt(path, exchange, control, reportSet, state) } finally { limiter.release() }
            if (outcome !is Outcome.Failure) { limiter.onSuccess(); return outcome }
            if (control.excused(epoch)) continue                         // spec 4.8: not counted, no limiter change, run again after the resume
            val transient = outcome.f == HttpFailure.Timeout || outcome.f == HttpFailure.Reset
            if (!transient) return outcome                               // Refused and Malformed: no retry
            limiter.onTimeoutOrReset()
            if (retried) return outcome
            retried = true
            delay(RETRY_DELAY_MS)
        }
    }

    /** HEAD then GET, or GET only; [RunControl.gate] (pause, then one rate token) runs before each request it sends. */
    private suspend fun attempt(path: String, exchange: Exchange, control: RunControl, reportSet: Set<Int>, state: State): Outcome {
        if (!state.getOnly) {
            control.gate()
            when (val head = exchange.request("HEAD", path)) {
                is HttpResult.Failed -> return Outcome.Failure(head.failure)
                is HttpResult.Ok -> {
                    val s = head.response.status
                    if (s == 405 || s == 501) { if (state.headFallbacks.incrementAndGet() >= HEAD_FALLBACK_SWITCH) state.getOnly = true }
                    else if (s !in reportSet) return Outcome.Miss
                }
            }
        }
        control.gate()
        return when (val get = exchange.request("GET", path)) {
            is HttpResult.Failed -> Outcome.Failure(get.failure)
            is HttpResult.Ok -> if (get.response.status in reportSet) Outcome.Hit(get.response) else Outcome.Miss
        }
    }

    private suspend fun report(item: Item, r: HttpResponse, target: ToolTarget, calibration: Calibration?, state: State, progress: ToolProgress) {
        val normalised = NotFoundFilter.normalisedSize(r, item.path)
        val location = if (r.status in 300..399) r.header("Location") else null
        val baseline = calibration?.baselines?.get(item.group)
        if (baseline != null && NotFoundFilter.isHidden(baseline, r.status, normalised, NotFoundFilter.normaliseLocation(location, item.path))) return
        if (state.end != null) return
        val slot = state.found.incrementAndGet()                   // claim the slot first, so concurrent hits never pass the cap (G22)
        if (slot > MAX_FINDINGS) return
        state.sample(r.status, normalised)
        val truncatedUnknown = r.bodyTruncated && r.contentLength == null
        val bytes = if (truncatedUnknown) null else (r.contentLength ?: r.body.size.toLong())
        val extra = buildMap { put("status", r.status.toString()); put("size", bytes?.toString() ?: ""); location?.let { put("location", it) } }
        progress.finding(Finding(FindingKind.PATH, item.path, ToolCopy.pathDetail(r.status, ToolCopy.size(bytes, truncatedUnknown), location), urlOf(target, item.path), extra))
        if (r.status == 200 || r.status == 204 || r.status == 401) progress.finding(Finding(FindingKind.HINT, "http.path=${item.path}"))
        if (slot == MAX_FINDINGS) state.endWith(ToolResult(ToolRunStatus.DONE, ToolCopy.BRUTE_CAP))
    }

    companion object {
        const val MAX_FINDINGS = 500; const val MAX_CONSECUTIVE_FAILURES = 50; const val HEAD_FALLBACK_SWITCH = 3
        const val RETRY_DELAY_MS = 250L; const val RUNAWAY_AFTER = 200; const val RUNAWAY_MIN_ALIKE = 50; const val RUNAWAY_BAND = 64L
        private const val CHANNEL_CAPACITY = 16; private const val MS_PER_S = 1000; private const val METHOD_USED = "methodUsed"

        /** spec 5.4.3: true when more than RUNAWAY_MIN_ALIKE findings share one status and their sizes lie within RUNAWAY_BAND bytes. */
        fun runaway(samples: List<Pair<Int, Long?>>): Boolean {
            for ((_, group) in samples.groupBy { it.first }) {
                val unknown = group.count { it.second == null }
                if (unknown > RUNAWAY_MIN_ALIKE) return true
                val sizes = group.mapNotNull { it.second }.sorted()
                var lo = 0
                for (hi in sizes.indices) {
                    while (sizes[hi] - sizes[lo] > RUNAWAY_BAND) lo++
                    if (hi - lo + 1 > RUNAWAY_MIN_ALIKE) return true
                }
            }
            return false
        }
    }
}
