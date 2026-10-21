package com.northwalessolutions.netprobe.scans

import com.northwalessolutions.netprobe.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val MS_PER_S = 1000L

/**
 * What is running app-wide, and how the last few things ended (3D spec 3.2). Memory only. [progress] stores into volatile counters and
 * never emits; one ticker publishes [live] every [PUBLISH_MS] while anything is live and ends itself with the last entry, as ToolRunner's
 * ticker does. Stop lambdas are held beside the data, not in [Live], so equality sees data only, and [end] drops the lambda so a cleared
 * ViewModel is never retained.
 */
class LiveWork(private val scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Kind { RADAR, QUICK_SCAN_ALL, PORT_SCAN, WORDLIST_IMPORT }
    enum class Outcome { DONE, STOPPED, FAILED, INTERRUPTED }
    data class Live(val id: Long, val kind: Kind, val networkId: Long?, val hostId: Long?, val label: String?, val startedAt: Long,
                    val done: Int, val total: Int?, val found: Int)
    data class Ended(val live: Live, val outcome: Outcome, val endedAt: Long?, val summary: String)

    private class Entry(val live: Live, @Volatile var stop: (() -> Unit)?) {
        @Volatile var done = live.done
        @Volatile var total = live.total
        @Volatile var found = live.found
        fun snapshot(): Live = live.copy(done = done, total = total, found = found)
    }

    private val lock = Any()
    private val entries = ConcurrentHashMap<Long, Entry>()
    private val seq = AtomicLong(0)
    private var ticker: Job? = null                       // guarded by lock
    private val _live = MutableStateFlow<List<Live>>(emptyList())
    private val _ended = MutableStateFlow<List<Ended>>(emptyList())
    val live: StateFlow<List<Live>> = _live
    val ended: StateFlow<List<Ended>> = _ended

    internal val heldStops: Int get() = entries.values.count { it.stop != null }
    internal val tickerRunning: Boolean get() = synchronized(lock) { ticker != null }

    fun now(): Long = clock()

    fun begin(kind: Kind, networkId: Long?, hostId: Long?, label: String?, stop: () -> Unit): Long {
        val id = seq.incrementAndGet()
        synchronized(lock) {
            entries[id] = Entry(Live(id, kind, networkId, hostId, label, clock(), 0, null, 0), stop)
            publishLocked(); ensureTickerLocked()
        }
        return id
    }

    fun progress(id: Long, done: Int, total: Int?, found: Int) {
        val e = entries[id] ?: return
        e.done = done; e.total = total; e.found = found
    }

    /** Idempotent; an unknown id does nothing, so a finally racing a clear or a second end is harmless. */
    fun end(id: Long, outcome: Outcome, summary: String) {
        synchronized(lock) {
            val e = entries.remove(id) ?: return
            val at = if (outcome == Outcome.INTERRUPTED) null else clock()
            _ended.value = (listOf(Ended(e.snapshot(), outcome, at, summary)) + _ended.value).take(RECENT_LIMIT)
            publishLocked()
        }
    }

    /** Removes a live entry without recording it: the work did not actually run. */
    fun drop(id: Long) {
        synchronized(lock) {
            if (entries.remove(id) != null) publishLocked()
        }
    }

    /** Calls the registered lambda once; a no-op after [end] or a second call. */
    fun stop(id: Long) {
        val f = synchronized(lock) { entries[id]?.let { e -> e.stop.also { e.stop = null } } }
        f?.invoke()
    }

    /** A launch-time entry for work a dead process left open (3D spec 3.3); it has no end time. */
    fun addInterrupted(kind: Kind, networkId: Long?, hostId: Long?, startedAt: Long, summary: String) {
        val id = seq.incrementAndGet()
        val e = Ended(Live(id, kind, networkId, hostId, null, startedAt, 0, null, 0), Outcome.INTERRUPTED, null, summary)
        synchronized(lock) { _ended.value = (listOf(e) + _ended.value).take(RECENT_LIMIT) }
    }

    fun clearEnded() { synchronized(lock) { _ended.value = emptyList() } }

    /** Undo of Clear history: merges the snapshot back with anything that ended since, newest first. */
    fun restoreEnded(snapshot: List<Ended>) {
        synchronized(lock) {
            _ended.value = (_ended.value + snapshot).distinctBy { it.live.id }
                .sortedByDescending { it.endedAt ?: it.live.startedAt }.take(RECENT_LIMIT)
        }
    }

    private fun publishLocked() {
        _live.value = entries.values.map { it.snapshot() }.sortedBy { it.id }
    }

    private fun ensureTickerLocked() {
        if (ticker != null) return
        ticker = scope.launch {
            while (true) {
                delay(PUBLISH_MS)
                // The emptiness check and clearing `ticker` share the lock with begin, so a begin racing the last end either finds this
                // ticker still live or starts a new one; a live entry is never left without a ticker.
                val last = synchronized(lock) { publishLocked(); entries.isEmpty().also { if (it) ticker = null } }
                if (last) break
            }
        }
    }

    companion object {
        const val RECENT_LIMIT = 50
        const val PUBLISH_MS = Motion.PROGRESS_SAMPLE_MS
    }
}

/**
 * Begin, run, end: the one place the outcome rules live, used by the radar scan, Quick scan all, port scans and the URL import. A body
 * that returns null did not run (the entry is dropped, nothing is recorded); a cancellation ends STOPPED with [stopped]; any other
 * throwable ends FAILED with [failed]; both are rethrown. [onEnd] runs just after the entry ends.
 */
suspend fun <T : Any> LiveWork.tracked(
    kind: LiveWork.Kind, networkId: Long?, hostId: Long?, label: String?, stop: () -> Unit,
    summarize: (T, Long) -> Pair<LiveWork.Outcome, String>, stopped: (Long) -> String, failed: (Throwable) -> String,
    onEnd: (LiveWork.Outcome, String) -> Unit = { _, _ -> },
    body: suspend (Long) -> T?,
): T? {
    val id = begin(kind, networkId, hostId, label, stop)
    val startedAt = now()
    fun seconds(): Long = ((now() - startedAt) / MS_PER_S).coerceAtLeast(0)
    try {
        val result = body(id)
        if (result == null) {
            drop(id)
            return null
        }
        val (outcome, summary) = summarize(result, seconds())
        end(id, outcome, summary); onEnd(outcome, summary)
        return result
    } catch (ce: CancellationException) {
        val summary = stopped(seconds())
        end(id, LiveWork.Outcome.STOPPED, summary); onEnd(LiveWork.Outcome.STOPPED, summary)
        throw ce
    } catch (t: Throwable) {
        val summary = failed(t)
        end(id, LiveWork.Outcome.FAILED, summary); onEnd(LiveWork.Outcome.FAILED, summary)
        throw t
    }
}
