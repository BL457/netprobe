package com.northwalessolutions.netprobe.ui.wordlists

import com.northwalessolutions.netprobe.engine.tools.AppMessages
import com.northwalessolutions.netprobe.wordlists.FetchFailure
import com.northwalessolutions.netprobe.wordlists.UrlImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    fun detach() { attached.decrementAndGet() }
    fun consumeMessage() { _message.value = null }

    /**
     * False while another URL import has not completed. isCompleted, not isActive: a cancelled job may still be inside its
     * NonCancellable store step, and a second import must not overlap it (plan review G10).
     */
    @Synchronized
    fun start(url: URL): Boolean {
        if (job?.isCompleted == false) return false
        _state.value = UrlImportState.Fetching(0, null)
        // LAZY, so [job] names this import before any of its code can call finish.
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext[Job]
            val result = try {
                fetch(url) { read, total -> _state.value = UrlImportState.Fetching(read, total) }
            } catch (ce: CancellationException) {
                finish(me, WordlistCopy.CANCELLED)
                throw ce
            } catch (e: Exception) {
                log(e)
                UrlImportResult.Failed(FetchFailure.Connection)
            }
            finish(me, WordlistImport.message(result))
        }
        job = next
        next.start()
        return true
    }

    fun cancel() { job?.cancel() }

    /** Only the current import ends the Fetching state; its message is delivered either way. */
    private fun finish(owner: Job?, text: String) {
        if (owner === job) _state.value = UrlImportState.Idle
        if (attached.get() > 0) _message.value = text else messages.post(text)
    }
}
