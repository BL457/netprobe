package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.script.ImportOutcome
import com.northwalessolutions.netprobe.engine.script.ImportPlan
import com.northwalessolutions.netprobe.engine.script.ScriptImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

/** Where the import flow is. Only [Review] holds a script; nothing has been saved before the Director taps Import. */
sealed interface ImportState {
    data object Closed : ImportState
    /** The first dialog: From file, or an address to fetch. */
    data object Choosing : ImportState
    data class Working(val fromUrl: Boolean) : ImportState
    data class Failed(val errors: List<String>) : ImportState
    data class Review(val plan: ImportPlan, val acknowledged: Boolean = false, val saving: Boolean = false) : ImportState
}

/**
 * The import flow's state machine (spec D10, G9). One lives in the app container, so the Tools tab and Settings share it and a rotation
 * does not lose a sheet. Reading, fetching and saving run on the app scope; Cancel stops the read or the fetch.
 */
class ToolImportController(private val scope: CoroutineScope, private val importer: ScriptImporter, private val log: (Throwable) -> Unit = {}) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Closed)
    val state: StateFlow<ImportState> = _state
    private val _notice = MutableStateFlow<String?>(null)
    /** One line for whichever screen is showing, after a save. */
    val notice: StateFlow<String?> = _notice
    private var job: Job? = null

    fun open() {
        if (_state.value is ImportState.Closed) _state.value = ImportState.Choosing
    }

    /** Closes whatever is open; a read or fetch in flight is stopped, a sheet is dropped without saving. */
    fun close() {
        job?.cancel()
        job = null
        _state.value = ImportState.Closed
    }

    fun importFile(fileName: String?, open: () -> InputStream?) { start(false) { importer.fromFile(fileName, open) } }

    fun importUrl(text: String) { start(true) { importer.fromUrl(text) } }

    private fun start(fromUrl: Boolean, body: suspend () -> ImportOutcome) {
        if (_state.value is ImportState.Working) return
        _state.value = ImportState.Working(fromUrl)
        job = scope.launch {
            val outcome = try {
                body()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                log(e)
                ImportOutcome.Failed(listOf(ImportCopy.UNEXPECTED))
            }
            _state.value = when (outcome) {
                is ImportOutcome.Ready -> ImportState.Review(outcome.plan)
                is ImportOutcome.Failed -> ImportState.Failed(outcome.errors)
            }
        }
    }

    /** Back from the error list to the first dialog. */
    fun again() {
        if (_state.value is ImportState.Failed) _state.value = ImportState.Choosing
    }

    fun setAcknowledged(on: Boolean) {
        val s = _state.value as? ImportState.Review ?: return
        _state.value = s.copy(acknowledged = on)
    }

    /** Import or Replace tapped. A widened scope is saved only after the extra yes. */
    fun confirm() {
        val s = _state.value as? ImportState.Review ?: return
        if (s.saving || (s.plan.needsWideningAck && !s.acknowledged)) return
        _state.value = s.copy(saving = true)
        job = scope.launch {
            val result = try {
                importer.confirm(s.plan, s.acknowledged)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                log(e)
                ScriptImporter.ConfirmResult.Refused(ImportCopy.SAVE_FAILED)
            }
            when (result) {
                is ScriptImporter.ConfirmResult.Saved -> { _notice.value = ImportCopy.imported(result.name, result.replaced); _state.value = ImportState.Closed }
                is ScriptImporter.ConfirmResult.Refused -> _state.value = ImportState.Failed(listOf(result.reason))
            }
        }
    }

    fun consumeNotice() { _notice.value = null }
}
