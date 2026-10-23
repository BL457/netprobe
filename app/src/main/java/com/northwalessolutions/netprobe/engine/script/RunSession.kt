package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.common.RateBucket
import com.northwalessolutions.netprobe.engine.tools.Finding
import com.northwalessolutions.netprobe.engine.tools.FindingKind
import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

fun interface MemoryProbe {
    /** Fraction of the heap limit in use, 0.0 to 1.0. */
    fun usedFraction(): Double
    companion object {
        val RUNTIME = MemoryProbe {
            val rt = Runtime.getRuntime()
            (rt.totalMemory() - rt.freeMemory()).toDouble() / rt.maxMemory().toDouble()
        }
    }
}

/**
 * State of one script run: the budgets (spec section 8, G4, G11), the abort flag, the sockets to close on Stop (G5) and the log. Every
 * host function goes through it. A host call blocks the script thread, so the suspending parts of the app (the run control's gate, the
 * progress sink) are reached with runBlocking on a Job that is a child of the tool run's own Job: cancelling the run cancels them.
 */
internal class RunSession(
    val limits: ScriptLimits,
    private val runJob: Job,
    val host: ScriptHost,
    val inputs: Map<String, Any?>,
    private val nanoClock: () -> Long = System::nanoTime,
    private val memory: MemoryProbe = MemoryProbe.RUNTIME,
) : IoScope {
    private val abortReason = AtomicReference<AbortReason?>(null)
    private val startedNs = nanoClock()
    @Volatile private var lastBeatNs = startedNs
    private val inHost = AtomicInteger(0)
    private val instructions = AtomicLong(0)
    private val calls = AtomicInteger(0)
    private val transferred = AtomicLong(0)
    private val findingCount = AtomicInteger(0)
    private val findingBytes = AtomicLong(0)
    private val open = ConcurrentHashMap.newKeySet<Closeable>()
    private val handles = AtomicInteger(0)
    private val logQueue = ArrayDeque<String>()
    private val findingBucket = RateBucket(limits.findingsPerSecond, limits.findingsBurst)

    /** Image ids this run's ctx.web.snapshot has issued, to the relative path of the stored picture. A finding accepts only these. */
    val images = ConcurrentHashMap<String, String>()
    val findings: Int get() = findingCount.get()
    val reason: AbortReason? get() = abortReason.get()
    fun logLines(): List<String> = synchronized(logQueue) { logQueue.toList() }

    // Aborting

    fun abort(why: AbortReason) { if (abortReason.compareAndSet(null, why)) closeAll() }
    private fun abortWith(why: AbortReason): Nothing { abort(why); throw ScriptAbort(why, ScriptCopy.abortSummary(why)) }

    override fun ensureAlive() {
        abortReason.get()?.let { throw ScriptAbort(it, ScriptCopy.abortSummary(it)) }
        if (!runJob.isActive) abortWith(AbortReason.CANCELLED)
        if ((nanoClock() - startedNs) / NANOS_PER_MS > limits.wallMs) abortWith(AbortReason.WALL_CLOCK)
    }

    /** The watchdog's verdict (G4): a reason to abandon the run even when the script thread is stuck in a native call, else null. */
    fun verdict(): AbortReason? {
        abortReason.get()?.let { return it }
        val now = nanoClock()
        if ((now - startedNs) / NANOS_PER_MS > limits.wallMs) return AbortReason.WALL_CLOCK
        if (memory.usedFraction() > limits.memoryFraction) return AbortReason.MEMORY
        if (inHost.get() == 0 && (now - lastBeatNs) / NANOS_PER_MS > limits.stallMs) return AbortReason.STALLED
        return null
    }

    /** The instruction observer (every INSTRUCTION_STEP instructions): heartbeat, instruction budget, abort flag, memory. */
    fun onInstructions(count: Int) {
        lastBeatNs = nanoClock()
        if (instructions.addAndGet(count.toLong()) > limits.maxInstructions) abortWith(AbortReason.INSTRUCTIONS)
        ensureAlive()
        if (memory.usedFraction() > limits.memoryFraction) abortWith(AbortReason.MEMORY)
    }

    private fun closeAll() { open.toList().forEach { runCatching { it.close() } }; open.clear() }
    override fun track(c: Closeable) { open.add(c); if (abortReason.get() != null) { runCatching { c.close() }; ensureAlive() } }
    override fun untrack(c: Closeable) { open.remove(c) }
    fun finish() { closeAll() }

    // Host calls

    inline fun <T> hostCall(block: () -> T): T {
        beginHost()
        try { return block() } finally { endHost() }
    }
    fun beginHost() { inHost.incrementAndGet(); lastBeatNs = nanoClock() }
    fun endHost() { inHost.decrementAndGet(); lastBeatNs = nanoClock() }

    /** Counts [cost] requests against the call budget (spec section 8). */
    fun chargeCalls(cost: Int) {
        ensureAlive()
        if (calls.addAndGet(cost) > limits.maxHostCalls) abortWith(AbortReason.CALLS)
    }

    /** The run's pause and rate gate, taken before each request (spec section 4). Waits while the app is in the background. */
    fun gateWait() {
        ensureAlive()
        blocking { host.control.gate() }
        ensureAlive()
    }

    fun addTransfer(bytes: Long) {
        if (transferred.addAndGet(bytes) > limits.maxTransferBytes) abortWith(AbortReason.TRANSFER)
    }

    fun <T> blocking(block: suspend () -> T): T {
        try {
            return runBlocking(runJob) { block() }
        } catch (e: CancellationException) {
            abort(AbortReason.CANCELLED); throw ScriptAbort(AbortReason.CANCELLED, ScriptCopy.abortSummary(AbortReason.CANCELLED))
        } catch (e: InterruptedException) {
            abort(AbortReason.CANCELLED); throw ScriptAbort(AbortReason.CANCELLED, ScriptCopy.abortSummary(AbortReason.CANCELLED))
        }
    }

    /** Sleeps in slices so a Stop, a budget or the watchdog ends it within a few tens of milliseconds. */
    fun sleep(ms: Long) {
        var left = ms
        while (left > 0) {
            ensureAlive()
            val slice = minOf(left, SLEEP_SLICE_MS)
            try { Thread.sleep(slice) } catch (_: InterruptedException) { abortWith(AbortReason.CANCELLED) }
            left -= slice
        }
        ensureAlive()
    }

    fun openHandle(): Boolean = handles.incrementAndGet() <= ScriptCaps.MAX_OPEN_HANDLES
    fun closeHandle() { handles.decrementAndGet() }

    // Findings (G11) and log

    fun finding(kind: String, value: String, detail: String?, url: String?, imageId: String? = null) {
        ensureAlive()
        val imagePath = imageId?.let { images[it] ?: throw ScriptHostError(ScriptCopy.IMAGE_UNKNOWN) }
        if (url != null && !isWebUrl(url)) throw ScriptHostError(ScriptCopy.FINDING_URL_NOT_WEB)
        // HINT findings are hidden from the lists and feed device identification (ToolDao.hintsOf), so a script may not write one.
        val known = FindingKind.entries.firstOrNull { it.name == kind && it != FindingKind.HINT }
        val v = cut(value); val d = detail?.let(::cut); val u = url?.let(::cut)
        val size = (kind.length + v.length + (d?.length ?: 0) + (u?.length ?: 0) + (imagePath?.length ?: 0)).toLong() * UTF8_WORST_CASE
        if (findingCount.incrementAndGet() > limits.maxFindings) abortWith(AbortReason.FINDINGS)
        if (findingBytes.addAndGet(size) > limits.maxFindingBytes) abortWith(AbortReason.FINDING_BYTES)
        while (true) {
            val wait = findingBucket.acquireDelayMs()
            if (wait == 0L) break
            sleep(wait)
        }
        val extra = buildMap<String, String> {
            if (known == null) put("kind", cut(kind))
            if (imagePath != null) put(Finding.IMAGE_KEY, imagePath)
        }
        blocking { host.progress.finding(Finding(known ?: FindingKind.OTHER, v, d, u, extra)) }
    }

    fun log(text: String) {
        synchronized(logQueue) {
            if (logQueue.size >= ScriptCaps.MAX_LOG_LINES) logQueue.removeFirst()
            logQueue.addLast(text.take(ScriptCaps.MAX_LOG_LINE_CHARS))
        }
    }

    private fun cut(s: String) = if (s.length <= ScriptCaps.MAX_FINDING_FIELD_CHARS) s else s.take(ScriptCaps.MAX_FINDING_FIELD_CHARS)

    companion object {
        private const val NANOS_PER_MS = 1_000_000L
        private const val SLEEP_SLICE_MS = 25L
        /** Bytes are counted as 3 per char, the worst case of UTF-8 for BMP text, so the 2 MB cap can never be exceeded on disk. */
        private const val UTF8_WORST_CASE = 3
        fun isWebUrl(url: String): Boolean {
            val u = url.trim()
            return (u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)) && u.none { it.code < 0x21 || it.code == 0x7f }
        }
    }
}
