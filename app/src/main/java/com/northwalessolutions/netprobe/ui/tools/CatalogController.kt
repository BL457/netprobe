package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.script.CatalogOutcome
import com.northwalessolutions.netprobe.engine.script.CatalogPlan
import com.northwalessolutions.netprobe.engine.script.CatalogUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where "Update tools from GitHub" is. Only [Review] holds fetched scripts; nothing is saved before Install. */
sealed interface CatalogState {
    data object Closed : CatalogState
    /** [total] is 0 while the index is being read; [installing] is true while Install is saving the tools. */
    data class Working(val done: Int = 0, val total: Int = 0, val installing: Boolean = false) : CatalogState
    /** [reason] is a Copy string, one line per problem. */
    data class Failed(val reason: String) : CatalogState
    data class Review(val plan: CatalogPlan, val acknowledged: Boolean = false) : CatalogState {
        /** Install is offered when something can be installed, and, when the plan widens access, after the extra yes. */
        val canInstall: Boolean get() = plan.installable > 0 && (!plan.needsYes || acknowledged)
    }
}

/**
 * The catalog action's state machine (spec section 11). One lives in the app container, so the Tools tab and Settings share it. Opening it
 * fetches the index and the changed scripts (the only network use of the feature, started by a tap); Cancel stops the fetch; Install saves
 * everything on the app scope so leaving the screen cannot stop it half way.
 */
class CatalogController(private val scope: CoroutineScope, private val updater: CatalogUpdater, private val log: (Throwable) -> Unit = {}) {
    private val _state = MutableStateFlow<CatalogState>(CatalogState.Closed)
    val state: StateFlow<CatalogState> = _state
    private val _notice = MutableStateFlow<String?>(null)
    /** One line for whichever screen is showing, after Install. */
    val notice: StateFlow<String?> = _notice
    private var job: Job? = null

    /** Starts a check. Does nothing while one is open. */
    fun open() {
        if (_state.value is CatalogState.Closed) check()
    }

    /** From the error dialog: check again. */
    fun retry() {
        if (_state.value is CatalogState.Failed) check()
    }

    private fun check() {
        _state.value = CatalogState.Working()
        job = scope.launch {
            val outcome = try {
                updater.check { done, total -> _state.value = CatalogState.Working(done, total) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                log(e)
                CatalogOutcome.Failed(ImportCopy.UNEXPECTED)
            }
            _state.value = when (outcome) {
                is CatalogOutcome.Ready -> CatalogState.Review(outcome.plan)
                is CatalogOutcome.Failed -> CatalogState.Failed(outcome.reason)
            }
        }
    }

    /** Closes whatever is open. A check in flight is stopped, a sheet is dropped without saving; an Install that has begun is not interrupted. */
    fun close() {
        if ((_state.value as? CatalogState.Working)?.installing == true) return
        job?.cancel()
        job = null
        _state.value = CatalogState.Closed
    }

    fun setAcknowledged(on: Boolean) {
        val s = _state.value as? CatalogState.Review ?: return
        _state.value = s.copy(acknowledged = on)
    }

    fun install() {
        val s = _state.value as? CatalogState.Review ?: return
        if (!s.canInstall) return
        _state.value = CatalogState.Working(installing = true)
        job = scope.launch {
            val applied = try {
                updater.apply(s.plan, s.acknowledged)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                log(e)
                _state.value = CatalogState.Failed(ImportCopy.SAVE_FAILED)
                return@launch
            }
            val failed = applied.failed
            when {
                failed.isEmpty() -> { _notice.value = CatalogCopy.installed(applied.installed.size); _state.value = CatalogState.Closed }
                applied.installed.isEmpty() -> _state.value = CatalogState.Failed(failed.joinToString("\n") { CatalogCopy.failureLine(it.name, it.reason) })
                else -> { _notice.value = CatalogCopy.installedSome(applied.installed.size, failed.size); _state.value = CatalogState.Closed }
            }
        }
    }

    fun consumeNotice() { _notice.value = null }
}
