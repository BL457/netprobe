package com.northwalessolutions.netprobe.engine.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/** One unread end-of-run message, read by the visible screen (Home notice line, Detail snackbar) (spec 4.3). */
data class AppMessage(val id: Long, val text: String, val hostId: Long?)
class AppMessages {
    private val seq = AtomicLong(0)
    private val _current = MutableStateFlow<AppMessage?>(null)
    val current: StateFlow<AppMessage?> = _current
    fun post(text: String, hostId: Long? = null) { _current.value = AppMessage(seq.incrementAndGet(), text, hostId) }
    fun dismiss(id: Long) { if (_current.value?.id == id) _current.value = null }
}
