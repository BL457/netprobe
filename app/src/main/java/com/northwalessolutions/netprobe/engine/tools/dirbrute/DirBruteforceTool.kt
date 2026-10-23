package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.HttpFailure
import com.northwalessolutions.netprobe.engine.common.HttpResponse
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.*
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolModelCopy
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
 *
 * With [DirBruteOptions.recursive] on, the run goes breadth first: level 0 is the start, and after each level every folder found
 * there (below maxDepth, within 50 per level and 200 in all) becomes a prefix the whole wordlist runs under in the next level.
 * A folder is a redirect to its own path plus a slash, or, for a bare word, a probe of that path plus a slash that answers 200, 204, 401 or 403.
 */
class DirBruteforceTool(
    private val exchangeFor: (target: ToolTarget, timeoutMs: Int) -> Exchange,
    private val resolve: suspend (wordlistId: String) -> LoadedWordlist?,
    private val random: () -> String = ::randomToken,
    private val clock: () -> Long = System::currentTimeMillis,
) : ToolModule {
    override val descriptor = ToolDescriptor(ToolKeys.DIR_BRUTEFORCE, ToolCopy.DIR_BRUTEFORCE, ToolModelCopy.DIR_BRUTEFORCE_DESCRIPTION, ToolIcon.FOLDER,
        ToolCategory.WEB, TargetKind.PORT, DirBruteInputs.specs)
    override fun check(inputs: ToolInputs): Map<String, String> = DirBruteInputs.check(inputs)
    override fun recorded(inputs: ToolInputs): ToolInputs = DirBruteInputs.recorded(inputs)
    override fun applies(port: Int, service: ServiceInfo) = ToolApplicability.appliesTo(descriptor.key, port, service)

    private data class Item(val path: String, val group: String)
    /** [Failure.headPassed]: the HEAD already answered, so a retry re-sends only the GET (and a 405 never counts twice). */
    private sealed interface Outcome { data class Hit(val r: HttpResponse) : Outcome; object Miss : Outcome; data class Failure(val f: HttpFailure, val headPassed: Boolean) : Outcome }

    /** Shared run state; every field is touched by the workers, so all are atomic or guarded. */
    private class State(getOnly: Boolean, startPaths: Int) {
        @Volatile var getOnly = getOnly
        val done = AtomicInteger(0); val found = AtomicInteger(0); val errors = AtomicInteger(0)
        val consecutive = AtomicInteger(0); val headFallbacks = AtomicInteger(0)
        /** Paths the run will try (grows as levels are added); [probes] are the extra folder requests, counted in both done and total. */
        val paths = AtomicInteger(startPaths); val probes = AtomicInteger(0)
        private var samples = ArrayList<Pair<Int, Long?>>()
        private val folders = FolderQueue()
        @Volatile var end: ToolResult? = null
        @Synchronized fun endWith(r: ToolResult) { if (end == null) end = r }
        @Synchronized fun sample(status: Int, size: Long?) { samples += status to size }
        @Synchronized fun snapshot(): List<Pair<Int, Long?>> = samples.toList()
        /** Each folder run is judged on its own answers, so one folder's alike hits never count against another's. */
        @Synchronized fun resetSamples() { samples = ArrayList() }
        /** [found] counts claimed slots, which can pass the cap by the few dropped claims (plan review G22). */
        fun foundCount(): Int = minOf(found.get(), MAX_FINDINGS)
        fun doneAll(): Int = done.get() + probes.get()
        fun totalAll(): Int = paths.get() + probes.get()
        fun folder(path: String, level: Int, maxDepth: Int) = folders.add(path, level, maxDepth)
        fun folderCount(): Int = folders.hits()
        fun takeNext(): List<String> = folders.takeNext()
    }

    /** What every worker of one run needs. */
    private class Session(val o: DirBruteOptions, val target: ToolTarget, val exchange: Exchange, val control: RunControl, val limiter: AdaptiveLimiter,
                          val reportSet: Set<Int>, val state: State, val progress: ToolProgress)

    override suspend fun run(target: ToolTarget?, inputs: ToolInputs, control: RunControl, progress: ToolProgress): ToolResult {
        if (target == null) return ToolResult(ToolRunStatus.FAILED, ToolCopy.failed(ToolModelCopy.NO_TARGET))
        val o = DirBruteInputs.options(inputs)
        val list = resolve(o.wordlistId) ?: return ToolResult(ToolRunStatus.FAILED, ToolCopy.failed(ToolCopy.BRUTE_NO_WORDLIST))
        val fellBack = list.id != o.wordlistId
        if (fellBack) {                                          // the chosen list is gone: optionsJson records what actually ran
            progress.option(WORDLIST_ID, list.id); progress.option(WORDLIST_NAME, list.name); progress.option(WORDLIST_ENTRIES, list.count)   // a JSON number, as encode writes it
        }
        val result = runList(list, o, target, control, progress)
        return if (fellBack) result.copy(summary = ToolCopy.bruteFallback(result.summary)) else result
    }

    private suspend fun runList(list: LoadedWordlist, o: DirBruteOptions, target: ToolTarget, control: RunControl, progress: ToolProgress): ToolResult {
        val timeoutMs = o.timeoutS * MS_PER_S
        val exchange = exchangeFor(target, timeoutMs)
        val perPrefix = list.count * (1 + o.extensions.size)
        val groups = listOf("") + o.extensions
        progress.update(0, perPrefix, 0, 0)

        var getOnly = o.method == DirMethod.GET_ONLY
        val rootCalibration = if (o.baseline) Calibrator.calibrate(groups, exchange, control, random) else null
        if (rootCalibration != null) {
            if (!rootCalibration.anyResponse) return ToolResult(ToolRunStatus.FAILED, ToolCopy.BRUTE_NO_ANSWER)
            if (rootCalibration.forceGetOnly && !getOnly) { getOnly = true; progress.option(METHOD_USED, DirMethod.GET_ONLY.name) }
        }
        val state = State(getOnly, perPrefix)
        val session = Session(o, target, exchange, control, AdaptiveLimiter(o.concurrency, clock, timeoutMs.toLong()), o.statusCodes.toSet(), state, progress)

        var prefixes = listOf("")
        var level = 0
        while (prefixes.isNotEmpty() && state.end == null) {
            if (o.recursive) progress.depth(level, o.maxDepth)
            for (prefix in prefixes) {
                if (state.end != null) break
                var calibration = rootCalibration
                if (prefix.isNotEmpty() && o.baseline) {
                    val folderCalibration = Calibrator.calibrate(groups, exchange, control, random, prefix)
                    if (!folderCalibration.anyResponse) {          // the folder went quiet: nothing to run under it
                        state.paths.addAndGet(-perPrefix); publish(state, progress); continue
                    }
                    if (folderCalibration.forceGetOnly && !state.getOnly) { state.getOnly = true; progress.option(METHOD_USED, DirMethod.GET_ONLY.name) }
                    calibration = folderCalibration
                }
                runPrefix(prefix, level, list, calibration, session)
            }
            level++
            prefixes = if (state.end == null && o.recursive) state.takeNext() else emptyList()
            if (prefixes.isNotEmpty()) { state.paths.addAndGet(prefixes.size * perPrefix); publish(state, progress) }
        }

        state.end?.let { return it }
        return ToolResult(ToolRunStatus.DONE, ToolCopy.bruteDone(state.foundCount(), state.doneAll(), state.errors.get(), if (o.recursive) state.folderCount() else null))
    }

    private fun publish(state: State, progress: ToolProgress) = progress.update(state.doneAll(), state.totalAll(), state.foundCount(), state.errors.get())

    /** The whole wordlist with its extensions under [prefix] ("" for the start, else a folder path with no trailing slash). */
    private suspend fun runPrefix(prefix: String, level: Int, list: LoadedWordlist, calibration: Calibration?, s: Session) {
        s.state.resetSamples()
        val levelDone = AtomicInteger(0)
        coroutineScope {
            val channel = Channel<Item>(capacity = CHANNEL_CAPACITY)
            val producer = launch {
                try {
                    list.stream { w ->
                        val bare = prefix + "/" + encodePath(w)
                        channel.send(Item(bare, groupOf(bare, "")))
                        for (ext in s.o.extensions) { val withExt = prefix + "/" + encodePath("$w.$ext"); channel.send(Item(withExt, groupOf(withExt, ext))) }
                    }
                } finally { channel.close() }
            }
            val workers = List(s.o.concurrency) {
                launch {
                    for (item in channel) {
                        if (s.state.end != null) break
                        handle(item, level, calibration, levelDone, s)
                    }
                }
            }
            workers.joinAll()
            producer.cancel()
        }
    }

    private suspend fun handle(item: Item, level: Int, calibration: Calibration?, levelDone: AtomicInteger, s: Session) {
        val state = s.state
        val outcome = attemptCounted(item.path, s.exchange, s.control, s.limiter, s.reportSet, state)
        val d = state.done.incrementAndGet()
        val n = levelDone.incrementAndGet()
        when (outcome) {
            is Outcome.Hit -> { state.consecutive.set(0); report(item, outcome.r, level, calibration, s) }
            is Outcome.Miss -> state.consecutive.set(0)
            is Outcome.Failure -> {
                state.errors.incrementAndGet()
                if (state.consecutive.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES)
                    state.endWith(ToolResult(ToolRunStatus.FAILED, ToolCopy.bruteStoppedAnswering(d)))
            }
        }
        publish(state, s.progress)
        if (n == RUNAWAY_AFTER && runaway(state.snapshot())) state.endWith(ToolResult(ToolRunStatus.DONE, ToolCopy.BRUTE_RUNAWAY))
    }

    /**
     * One path to a counted outcome: excused failures retry after the resume; Timeout and Reset retry once after RETRY_DELAY_MS.
     * A retry re-sends only the step that failed: once the HEAD has answered, only the GET goes again.
     * The gate is not taken here: [attempt] takes it before every request, so a HEAD then GET costs two rate tokens (plan review G3).
     * The epoch is read before the first gate, so a pause that starts while this path waits at the gate also excuses its failure:
     * at worst one uncounted extra try, never a counted error the Director caused.
     * [accept] is the set of statuses that make a Hit: the report set for a wordlist path, the folder codes for a probe.
     */
    private suspend fun attemptCounted(path: String, exchange: Exchange, control: RunControl, limiter: AdaptiveLimiter, accept: Set<Int>, state: State): Outcome {
        var retried = false; var headPassed = false
        while (true) {
            val epoch = control.pauseEpoch
            limiter.acquire()
            val outcome = try { attempt(path, exchange, control, accept, state, headPassed) } finally { limiter.release() }
            if (outcome !is Outcome.Failure) { limiter.onSuccess(); return outcome }
            headPassed = outcome.headPassed
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
    private suspend fun attempt(path: String, exchange: Exchange, control: RunControl, accept: Set<Int>, state: State, headPassed: Boolean): Outcome {
        if (!headPassed && !state.getOnly) {
            control.gate()
            when (val head = exchange.request("HEAD", path)) {
                is HttpResult.Failed -> return Outcome.Failure(head.failure, headPassed = false)
                is HttpResult.Ok -> {
                    val s = head.response.status
                    if (s == 405 || s == 501) { if (state.headFallbacks.incrementAndGet() >= HEAD_FALLBACK_SWITCH) state.getOnly = true }
                    else if (s !in accept) return Outcome.Miss
                }
            }
        }
        control.gate()
        return when (val get = exchange.request("GET", path)) {
            is HttpResult.Failed -> Outcome.Failure(get.failure, headPassed = true)
            is HttpResult.Ok -> if (get.response.status in accept) Outcome.Hit(get.response) else Outcome.Miss
        }
    }

    private suspend fun report(item: Item, r: HttpResponse, level: Int, calibration: Calibration?, s: Session) {
        val state = s.state
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
        val folder = redirectsToFolder(r.status, location, item.path) || (s.o.recursive && isBareWord(item.group) && probeIsFolder(item.path, s))
        if (folder) state.folder(item.path, level, s.o.maxDepth)
        val extra = buildMap { put("status", r.status.toString()); put("size", bytes?.toString() ?: ""); location?.let { put("location", it) }; if (folder) put("folder", "true") }
        s.progress.finding(Finding(FindingKind.PATH, item.path, ToolCopy.pathDetail(r.status, ToolCopy.size(bytes, truncatedUnknown), location, folder), urlOf(s.target, item.path), extra))
        if (r.status == 200 || r.status == 204 || r.status == 401) s.progress.finding(Finding(FindingKind.HINT, "http.path=${item.path}"))
        if (slot == MAX_FINDINGS) state.endWith(ToolResult(ToolRunStatus.DONE, ToolCopy.BRUTE_CAP))
    }

    /** One extra request to [path] plus a slash, sent like any attempt (limiter, gate, HEAD then GET); a folder answers one of [PROBE_CODES]. A failed probe is just "not a folder". */
    private suspend fun probeIsFolder(path: String, s: Session): Boolean {
        val outcome = attemptCounted("$path/", s.exchange, s.control, s.limiter, PROBE_CODES, s.state)
        s.state.probes.incrementAndGet()
        publish(s.state, s.progress)
        return outcome is Outcome.Hit
    }

    private fun isBareWord(group: String): Boolean = group == "" || group == dotGroup("")

    companion object {
        const val MAX_FINDINGS = 500; const val MAX_CONSECUTIVE_FAILURES = 50; const val HEAD_FALLBACK_SWITCH = 3
        const val RETRY_DELAY_MS = 250L; const val RUNAWAY_AFTER = 200; const val RUNAWAY_MIN_ALIKE = 50; const val RUNAWAY_BAND = 64L
                private val FOLDER_REDIRECTS = setOf(301, 302, 307, 308); private val PROBE_CODES = setOf(200, 204, 401, 403)
        private const val SCHEME_SEPARATOR = "://"
        private const val CHANNEL_CAPACITY = 16; private const val MS_PER_S = 1000; private const val METHOD_USED = "methodUsed"
        private const val WORDLIST_ID = "wordlistId"; private const val WORDLIST_NAME = "wordlistName"; private const val WORDLIST_ENTRIES = "wordlistEntries"

        /** The path part of a Location: scheme and host dropped, query and fragment cut. */
        internal fun locationPath(location: String): String {
            val at = location.indexOf(SCHEME_SEPARATOR)
            val absolute = at > 0 && location.substring(0, at).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
            val path = if (absolute) location.substring(at + SCHEME_SEPARATOR.length).let { rest -> rest.indexOf('/').let { i -> if (i < 0) "/" else rest.substring(i) } } else location
            return path.substringBefore('?').substringBefore('#')
        }

        /** A 301, 302, 307 or 308 whose Location is [path] plus a slash, ignoring scheme and host. */
        internal fun redirectsToFolder(status: Int, location: String?, path: String): Boolean =
            status in FOLDER_REDIRECTS && location != null && locationPath(location) == "$path/"

        /**
         * spec 5.4.3: true when more than RUNAWAY_MIN_ALIKE findings share one status and their sizes lie within RUNAWAY_BAND bytes.
         * Only findings are sampled ([report] samples after the not-found filter), so an answer calibration classified as not-found,
         * including a dot-path 403 banded by the dot twin, never counts; a catch-all calibration could not band still does.
         */
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

/**
 * The folders a recursive run has found: each is counted once, and is queued for the next level only below `maxDepth` and inside
 * the caps ([perLevel] queued per level, [total] queued in all). Folders past a cap are still counted and reported, just not descended.
 */
internal class FolderQueue(private val perLevel: Int = MAX_FOLDERS_PER_LEVEL, private val total: Int = MAX_FOLDERS_TOTAL) {
    private val seen = HashSet<String>(); private val next = ArrayList<String>()
    private var queued = 0; private var hits = 0
    @Synchronized fun add(path: String, level: Int, maxDepth: Int) {
        if (!seen.add(path)) return
        hits++
        if (level >= maxDepth || next.size >= perLevel || queued >= total) return
        next += path; queued++
    }
    @Synchronized fun hits(): Int = hits
    /** The folders queued since the last call, in path order so a capped run is repeatable. */
    @Synchronized fun takeNext(): List<String> { val r = next.sorted(); next.clear(); return r }
    companion object { const val MAX_FOLDERS_PER_LEVEL = 50; const val MAX_FOLDERS_TOTAL = 200 }
}
