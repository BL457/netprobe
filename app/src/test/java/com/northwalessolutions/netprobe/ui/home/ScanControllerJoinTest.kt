package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanControllerJoinTest {
    @Test fun `stop and join waits for the scan's cleanup`() = runTest {
        val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home); val events = mutableListOf<String>()
        val c = ScanController(backgroundScope, env) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { delay(100); events += "cleanup" } }
        }
        c.start(); runCurrent()
        c.stopAndJoin(); events += "joined"
        assertEquals(listOf("cleanup", "joined"), events); assertFalse(c.state.value.scanning)
    }
    @Test fun `stop and join with nothing running returns at once`() = runTest {
        val c = ScanController(backgroundScope, MutableStateFlow<NetworkEnv?>(TestEnvs.home)) { }
        c.stopAndJoin(); assertFalse(c.isRunning)
    }
}
