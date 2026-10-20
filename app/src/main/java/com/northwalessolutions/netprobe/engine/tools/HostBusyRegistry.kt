package com.northwalessolutions.netprobe.engine.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One app-level claim per host: a port scan or a tool, never both (spec 4.3). Consulted by Quick scan all, Detail port scans and the tool runner. */
class HostBusyRegistry {
    enum class Work { PORT_SCAN, TOOL }
    private val _busy = MutableStateFlow<Map<Long, Work>>(emptyMap())
    val busy: StateFlow<Map<Long, Work>> = _busy

    @Synchronized fun tryClaim(hostId: Long, work: Work): Boolean {
        if (_busy.value.containsKey(hostId)) return false
        _busy.value = _busy.value + (hostId to work); return true
    }
    @Synchronized fun release(hostId: Long, work: Work) {
        if (_busy.value[hostId] == work) _busy.value = _busy.value - hostId
    }
}
