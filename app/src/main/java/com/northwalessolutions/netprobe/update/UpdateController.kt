package com.northwalessolutions.netprobe.update

import com.northwalessolutions.netprobe.engine.tools.AppMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** What the Home banner shows (spec 3). */
sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(val manifest: UpdateManifest, val percent: Int?) : UpdateState
    data class Verifying(val manifest: UpdateManifest) : UpdateState
    data class Ready(val manifest: UpdateManifest, val file: File) : UpdateState
    data class Failed(val manifest: UpdateManifest, val text: String) : UpdateState
}

/**
 * The updater's state machine on appScope, so leaving Home never cancels a download. Automatic checks run on foreground, gated by the
 * toggle, the network and the 24 h stamp (the checker). A manual check reports to the attached Settings screen, else to [messages].
 * Nothing installs by itself: a finished download is offered to the system installer, which asks the user again.
 */
class UpdateController(private val scope: CoroutineScope, private val checker: UpdateChecker, private val installer: UpdateInstaller,
                       private val messages: AppMessages, private val autoEnabled: () -> Boolean, private val networkAvailable: () -> Boolean,
                       private val log: (Throwable) -> Unit = {}) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking
    private val _result = MutableStateFlow<String?>(null)
    /** The last manual check's text for the attached Settings screen. */
    val result: StateFlow<String?> = _result
    private val _lastCheck = MutableStateFlow(checker.lastCheckEpochMs())
    /** When the last check ran (epoch ms, 0 for never); moves when any check finishes. */
    val lastCheck: StateFlow<Long> = _lastCheck
    private val installChannel = Channel<File>(Channel.CONFLATED)
    /** A verified APK ready for the system installer; collected by MainActivity. */
    val installRequests = installChannel.receiveAsFlow()
    private val attached = AtomicInteger(0)
    @Volatile private var checkJob: Job? = null
    @Volatile private var downloadJob: Job? = null

    fun attach() { attached.incrementAndGet() }
    fun detach() { if (attached.decrementAndGet() <= 0) _result.value = null }

    /** MainActivity came to the foreground. */
    fun onForeground() {
        if (!autoEnabled() || !networkAvailable()) return
        runCheck(manual = false)
    }

    fun checkNow() { runCheck(manual = true) }

    @Synchronized
    private fun runCheck(manual: Boolean) {
        if (checkJob?.isCompleted == false) return
        if (manual) { _result.value = null; _checking.value = true }
        checkJob = scope.launch {
            try {
                apply(checker.check(manual), manual)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                log(e)
                if (manual) report(UpdateCopy.CHECK_FAILED)
            } finally {
                _lastCheck.value = checker.lastCheckEpochMs()
                if (manual) _checking.value = false
            }
        }
    }

    private fun apply(r: CheckResult, manual: Boolean) {
        when (r) {
            is CheckResult.Available -> {
                if (_state.value is UpdateState.Idle || _state.value is UpdateState.Available) _state.value = UpdateState.Available(r.manifest)
                if (manual) report(UpdateCopy.available(r.manifest.versionName))
            }
            CheckResult.UpToDate -> if (manual) report(UpdateCopy.UP_TO_DATE)
            CheckResult.Invalid -> if (manual) report(UpdateCopy.BAD_MANIFEST)
            CheckResult.Failed -> if (manual) report(UpdateCopy.CHECK_FAILED)
            CheckResult.NotDue, CheckResult.Skipped -> Unit
        }
    }

    private fun report(text: String) { if (attached.get() > 0) _result.value = text else messages.post(text) }

    /** "Later": hide this version from automatic checks. */
    fun later() {
        val s = _state.value
        val manifest = (s as? UpdateState.Available)?.manifest ?: (s as? UpdateState.Failed)?.manifest ?: return
        checker.skip(manifest.versionCode)
        _state.value = UpdateState.Idle
    }

    /** Update or Retry. The caller has already made sure the install permission is held. */
    @Synchronized
    fun download() {
        val s = _state.value
        val manifest = (s as? UpdateState.Available)?.manifest ?: (s as? UpdateState.Failed)?.manifest ?: return
        if (downloadJob?.isCompleted == false) return
        _state.value = UpdateState.Downloading(manifest, null)
        downloadJob = scope.launch {
            try {
                var lastPercent: Int? = -1
                val prep = installer.download(manifest, { done, total ->
                    val p = if (total != null && total > 0) (done * PERCENT / total).toInt().coerceIn(0, PERCENT.toInt()) else null
                    if (p != lastPercent) { lastPercent = p; _state.value = UpdateState.Downloading(manifest, p) }
                }, onVerifying = { _state.value = UpdateState.Verifying(manifest) })
                when (prep) {
                    is PrepResult.Ready -> { _state.value = UpdateState.Ready(manifest, prep.file); installChannel.trySend(prep.file) }
                    is PrepResult.Failed -> _state.value = UpdateState.Failed(manifest, UpdateCopy.failure(prep.reason))
                }
            } catch (ce: CancellationException) {
                _state.value = UpdateState.Available(manifest)   // Stop: the installer already removed the part file
                throw ce
            } catch (e: Exception) {
                log(e)
                _state.value = UpdateState.Failed(manifest, UpdateCopy.FAIL_DOWNLOAD)
            }
        }
    }

    fun stop() { downloadJob?.cancel() }

    /** Install, after the user dismissed the installer. */
    fun reinstall() { (_state.value as? UpdateState.Ready)?.let { installChannel.trySend(it.file) } }

    fun cleanUp() { installer.housekeeping() }

    private companion object { const val PERCENT = 100L }
}
