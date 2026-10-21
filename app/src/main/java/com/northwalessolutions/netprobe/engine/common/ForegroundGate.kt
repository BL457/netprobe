package com.northwalessolutions.netprobe.engine.common

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Suspends while NetProbe is in the background (3D spec 3.3). The signal is the same lifecycle flow ToolRunner reads
 * (AppContainer.foreground), so a port scan and a tool run pause and resume together.
 */
class ForegroundGate(private val foreground: StateFlow<Boolean>) {
    suspend fun await() {
        if (!foreground.value) foreground.first { it }
    }
}
