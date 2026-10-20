package com.northwalessolutions.netprobe

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One Undo offered in the notice line (spec 5.3). [commit] makes the action final; [restore] reverses it. */
class PendingUndo(val text: String, val restore: suspend () -> Unit, val commit: suspend () -> Unit)

/**
 * At most one pending Undo, memory only (spec 5.3, A6). It is committed when the notice is dismissed, when a new Undo replaces it,
 * and at the very start of every scan body, so a restore can never land while a scan is writing.
 */
class UndoStore {
    private val mutex = Mutex()
    private val _pending = MutableStateFlow<PendingUndo?>(null)
    val pending: StateFlow<PendingUndo?> = _pending

    /** Commits the previous Undo first. */
    suspend fun post(next: PendingUndo) {
        mutex.withLock {
            val previous = _pending.value
            _pending.value = null
            if (previous != null) withContext(NonCancellable) { previous.commit() }
            _pending.value = next
        }
    }
    suspend fun undo() {
        mutex.withLock {
            val p = _pending.value ?: return@withLock
            _pending.value = null
            withContext(NonCancellable) { p.restore() }   // a cancelled caller must not leave a half-applied Undo
        }
    }
    /** Idempotent: a second call finds nothing pending. */
    suspend fun commit() {
        mutex.withLock {
            val p = _pending.value ?: return@withLock
            _pending.value = null
            withContext(NonCancellable) { p.commit() }    // a Stop or ViewModel clear must not abandon the cascade midway
        }
    }
    /** Every scan body (radar, Quick scan all, Detail) runs inside this, so a pending Undo is final before the scan's first lookup. */
    suspend fun <T> committedBefore(body: suspend () -> T): T {
        commit()
        return body()
    }
}

object AppScopes {
    const val LOG_TAG = "NetProbe"
    /** Logs and never rethrows: an unexpected failure in a delete or restore can never crash the app (spec 5.3). */
    fun handler(log: (Throwable) -> Unit): CoroutineExceptionHandler = CoroutineExceptionHandler { _, t -> log(t) }
    fun create(handler: CoroutineExceptionHandler): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
}
