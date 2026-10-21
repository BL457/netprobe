package com.northwalessolutions.netprobe.engine.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/** One unread end-of-run message, read by the visible screen (Home notice line, Detail snackbar) (spec 4.3). */
data class AppMessage(val id: Long, val text: String, val hostId: Long?)

/**
 * A short FIFO queue of unread end-of-run messages, drained one at a time (final review S8). [current] is the head, which is what every
 * screen already shows; dismissing it brings the next one up, so two scans ending together are both seen. At most [CAPACITY] wait: a
 * further post drops the oldest message that is not the head (the head may be on screen right now).
 */
class AppMessages {
    private val seq = AtomicLong(0)
    private val lock = Any()
    private val queue = ArrayDeque<AppMessage>()                 // guarded by lock; the head is also published as current
    private val _current = MutableStateFlow<AppMessage?>(null)
    val current: StateFlow<AppMessage?> = _current

    fun post(text: String, hostId: Long? = null) {
        synchronized(lock) {
            if (queue.size >= CAPACITY) queue.removeAt(1)
            queue.addLast(AppMessage(seq.incrementAndGet(), text, hostId))
            _current.value = queue.first()
        }
    }

    /** Removes the head if its id still matches; a stale dismiss is ignored. */
    fun dismiss(id: Long) {
        synchronized(lock) {
            if (queue.firstOrNull()?.id != id) return
            queue.removeFirst()
            _current.value = queue.firstOrNull()
        }
    }

    companion object { const val CAPACITY = 3 }
}
