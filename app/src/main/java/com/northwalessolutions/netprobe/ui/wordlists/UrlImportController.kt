package com.northwalessolutions.netprobe.ui.wordlists

import com.northwalessolutions.netprobe.engine.tools.AppMessages
import com.northwalessolutions.netprobe.wordlists.FetchFailure
import com.northwalessolutions.netprobe.wordlists.UrlImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

sealed interface UrlImportState {
    object Idle : UrlImportState
    data class Fetching(val read: Long, val total: Long?) : UrlImportState
}

/**
 * One URL import at a time on [scope] (appScope in the app), so leaving the Wordlists screen does not cancel it (3C spec 5.1). Cancel
 * cancels the job; the fetch disconnects at once and stores nothing. The result text goes to the attached screen's snackbar through
 * [message], else to [messages] (the Home notice line).
 */
class UrlImportController(private val scope: CoroutineScope, private val fetch: suspend (URL, (Long, Long?) -> Unit) -> UrlImportResult,
                          private val messages: AppMessages, private val log: (Throwable) -> Unit = {}) {
    private val _state = MutableStateFlow<UrlImportState>(UrlImportState.Idle)
    val state: StateFlow<UrlImportState> = _state
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val attached = AtomicInteger(0)
    @Volatile private var job: Job? = null

    fun attach() { attached.incrementAndGet() }
    /** The last screen leaving hands any result it never consumed to [messages], so it is not shown again on the next visit. */
    fun detach() {
        if (attached.decrementAndGet() <= 0) _message.getAndUpdate { null }?.let { messages.post(it) }
    }
    fun consumeMessage() { _message.value = null }

    /**
     * False while another URL import has not completed. isCompleted, not isActive: a cancelled job may still be inside its
     * NonCancellable store step, and a second import must not overlap it (plan review G10).
     */
    @Synchronized
    @OptIn(DelicateCoroutinesApi::class)                     // CoroutineStart.ATOMIC, as in ToolRunner
    fun start(url: URL): Boolean {
        if (job?.isCompleted == false) return false
        _state.value = UrlImportState.Fetching(0, null)
        // ATOMIC, not LAZY: a job cancelled before its first dispatch still runs its body, so finish always runs and Fetching cannot stick.
        // The body's first step takes this lock, so [job] names this import before finish compares against it.
        val next = scope.launch(start = CoroutineStart.ATOMIC) {
            val me = coroutineContext[Job]
            synchronized(this@UrlImportController) { }
            var text = WordlistImport.failure(FetchFailure.Connection)    // what a non-Exception Throwable leaves behind
            try {
                text = WordlistImport.message(fetch(url) { read, total -> _state.value = UrlImportState.Fetching(read, total) })
            } catch (ce: CancellationException) {
                text = WordlistCopy.CANCELLED
                throw ce
            } catch (e: Exception) {
                log(e)
            } finally {
                finish(me, text)
            }
        }
        job = next
        return true
    }

    fun cancel() { job?.cancel() }

    /** Only the current import ends the Fetching state; its message is delivered either way. */
    private fun finish(owner: Job?, text: String) {
        if (owner === job) _state.value = UrlImportState.Idle
        if (attached.get() > 0) _message.value = text else messages.post(text)
    }
}
