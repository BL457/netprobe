package com.northwalessolutions.netprobe.oui

import com.northwalessolutions.netprobe.engine.tools.AppMessages
import com.northwalessolutions.netprobe.scans.LiveWork
import com.northwalessolutions.netprobe.scans.tracked
import com.northwalessolutions.netprobe.ui.wordlists.WordlistImport
import com.northwalessolutions.netprobe.wordlists.ImportResult
import com.northwalessolutions.netprobe.wordlists.UrlImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

sealed interface OuiUpdateState {
    object Idle : OuiUpdateState
    /** [step] of [OuiSources.ALL] is being downloaded. */
    data class Running(val step: Int, val total: Int) : OuiUpdateState
}

/** The three IEEE registry files, in the order they are fetched (MA-L, MA-M, MA-S). */
object OuiSources {
    val ALL = listOf(
        "https://standards-oui.ieee.org/oui/oui.csv",
        "https://standards-oui.ieee.org/oui28/mam.csv",
        "https://standards-oui.ieee.org/oui36/oui36.csv",
    )
    /** Per-file cap (the wordlist fetcher's 10 MB cap, held to 8 MB here). */
    const val MAX_FILE_BYTES = 8L * 1024 * 1024
}

/**
 * "Update vendor database" (Director ruling 2026-10-22 b). One update at a time on [scope] (appScope), so leaving Settings does not
 * cancel it. The three files are fetched with the wordlist fetcher (via [fetch]); nothing is written unless all three arrive and
 * parse, and the table is replaced atomically, so a failure or a Stop leaves the old table as it was. The result text goes to the
 * attached Settings screen, else to [messages].
 */
class OuiUpdater(private val scope: CoroutineScope, private val store: OuiStore, private val messages: AppMessages,
                 private val fetch: suspend (URL, Long, (Long, Long?) -> Unit) -> UrlImportResult,
                 private val now: () -> Long = System::currentTimeMillis, private val log: (Throwable) -> Unit = {},
                 private val liveWork: LiveWork? = null, private val sources: List<String> = OuiSources.ALL) {
    private val _state = MutableStateFlow<OuiUpdateState>(OuiUpdateState.Idle)
    val state: StateFlow<OuiUpdateState> = _state
    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result
    private val attached = AtomicInteger(0)
    @Volatile private var job: Job? = null

    fun attach() { attached.incrementAndGet() }
    fun detach() { if (attached.decrementAndGet() <= 0) _result.getAndUpdate { null } }

    private data class Done(val text: String, val outcome: LiveWork.Outcome)

    @Synchronized
    @OptIn(DelicateCoroutinesApi::class)                     // CoroutineStart.ATOMIC, as in UrlImportController
    fun start(): Boolean {
        if (job?.isCompleted == false) return false
        _result.value = null
        _state.value = OuiUpdateState.Running(1, sources.size)
        val next = scope.launch(start = CoroutineStart.ATOMIC) {
            val me = coroutineContext[Job]
            synchronized(this@OuiUpdater) { }
            var text = OuiCopy.CANCELLED
            try {
                text = tracked().text
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                log(e)
                text = WordlistImport.failure(com.northwalessolutions.netprobe.wordlists.FetchFailure.Connection)
            } finally {
                if (me === job) _state.value = OuiUpdateState.Idle
                if (attached.get() > 0) _result.value = text else messages.post(text)
            }
        }
        job = next
        return true
    }

    fun cancel() { job?.cancel() }

    private suspend fun tracked(): Done {
        val live = liveWork ?: return fetchAll { _ -> }
        val result = live.tracked<Done>(LiveWork.Kind.VENDOR_DB, null, null, null, stop = { cancel() },
            summarize = { d, _ -> d.outcome to d.text }, stopped = { OuiCopy.CANCELLED },
            failed = { WordlistImport.failure(com.northwalessolutions.netprobe.wordlists.FetchFailure.Connection) }) { id ->
            fetchAll { step -> live.progress(id, step, sources.size, 0) }
        }
        return checkNotNull(result) { "the update body never returns null" }
    }

    private suspend fun fetchAll(onStep: (Int) -> Unit): Done {
        val builder = OuiTable.Builder()
        for ((i, source) in sources.withIndex()) {
            val step = i + 1
            _state.value = OuiUpdateState.Running(step, sources.size)
            onStep(step)
            when (val r = fetch(URL(source), OuiSources.MAX_FILE_BYTES) { _, _ -> }) {
                is UrlImportResult.Fetched -> if (OuiCsv.parseInto(String(r.bytes, Charsets.UTF_8), builder) == 0) return failed(OuiCopy.EMPTY)
                is UrlImportResult.Failed -> return failed(WordlistImport.failure(r.reason))
                UrlImportResult.Cancelled -> return Done(OuiCopy.CANCELLED, LiveWork.Outcome.STOPPED)
                is UrlImportResult.Stored -> return failed(if (r.result == ImportResult.TooLarge) OuiCopy.TOO_LARGE else OuiCopy.UNREADABLE)
            }
        }
        val table = builder.build()
        // Stop is honoured up to here; the write itself is one atomic step that a Stop never lands inside.
        currentCoroutineContext().ensureActive()
        try { withContext(NonCancellable) { store.replace(table, now()) } } catch (e: java.io.IOException) { log(e); return failed(OuiCopy.NOT_SAVED) }
        return Done(OuiCopy.updated(table.size), LiveWork.Outcome.DONE)
    }

    private fun failed(text: String) = Done(text, LiveWork.Outcome.FAILED)
}
