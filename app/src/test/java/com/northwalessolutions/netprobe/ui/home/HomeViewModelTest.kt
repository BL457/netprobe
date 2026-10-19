package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    // runCurrent, not advanceUntilIdle: since kotlinx-coroutines-test 1.7, advanceUntilIdle returns once only backgroundScope work is left.
    /** The scan controller is the testable core of HomeViewModel: it owns the job and reacts to env changes. */
    @Test fun `network loss cancels an active scan and sets the message`() = runTest {
        val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home)
        var cancelled = false
        val c = ScanController(scope = backgroundScope, env = env, runDiscovery = { try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true } })
        c.start(); runCurrent(); assertTrue(c.state.value.scanning)
        env.value = null; runCurrent()
        assertTrue(cancelled); assertFalse(c.state.value.scanning); assertEquals("Network changed, scan stopped", c.state.value.message)
    }
    @Test fun `switching networks mid scan also cancels`() = runTest {
        val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home)
        val c = ScanController(scope = backgroundScope, env = env, runDiscovery = { kotlinx.coroutines.awaitCancellation() })
        c.start(); runCurrent(); env.value = TestEnvs.office; runCurrent()
        assertFalse(c.state.value.scanning); assertNotNull(c.state.value.message)
    }
    @Test fun `stop clears scanning without a message and a completed scan clears scanning`() = runTest {
        val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home)
        val c = ScanController(scope = backgroundScope, env = env, runDiscovery = { })
        c.start(); runCurrent(); assertFalse(c.state.value.scanning); assertNull(c.state.value.message)
        val c2 = ScanController(scope = backgroundScope, env = env, runDiscovery = { kotlinx.coroutines.awaitCancellation() })
        c2.start(); runCurrent(); c2.stop(); runCurrent(); assertFalse(c2.state.value.scanning); assertNull(c2.state.value.message)
    }
    @Test fun `start without a network sets a message and does not scan`() = runTest {
        val c = ScanController(scope = backgroundScope, env = MutableStateFlow(null), runDiscovery = { fail("must not run") })
        c.start(); runCurrent(); assertFalse(c.state.value.scanning); assertEquals("Connect to Wi-Fi or Ethernet to scan", c.state.value.message)
    }
    // Added beyond the brief: a thrown discovery error must end the scan with a message, never crash the app.
    @Test fun `a failing discovery stops scanning with a message`() = runTest {
        val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home)
        val c = ScanController(scope = backgroundScope, env = env, runDiscovery = { throw IOException("socket closed") })
        c.start(); runCurrent()
        assertFalse(c.state.value.scanning); assertEquals("Scan failed: socket closed", c.state.value.message)
    }
    // Added beyond the brief: Stop then Scan straight away must not let the old job's cleanup end the new scan.
    @Test fun `restart after stop keeps the new scan running`() = runTest {
        val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home)
        val c = ScanController(scope = backgroundScope, env = env, runDiscovery = { kotlinx.coroutines.awaitCancellation() })
        c.start(); runCurrent(); c.stop(); c.start(); runCurrent()
        assertTrue(c.state.value.scanning)
        env.value = TestEnvs.office; runCurrent()
        assertFalse(c.state.value.scanning); assertEquals("Network changed, scan stopped", c.state.value.message)
    }
    // Fix round 1: a cancelled run may still be releasing locks; the next run must not start its work until that cleanup has finished.
    @Test fun `a new scan waits for the previous run's cleanup before starting`() = runTest {
        val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home)
        val events = mutableListOf<String>()
        var run = 0
        val c = ScanController(scope = backgroundScope, env = env, runDiscovery = {
            val n = ++run
            events += "acquire$n"
            try { kotlinx.coroutines.awaitCancellation() } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { kotlinx.coroutines.delay(100) }   // slow IO teardown
                events += "release$n"
            }
        })
        c.start(); runCurrent(); c.stop(); c.start(); runCurrent()
        assertEquals(listOf("acquire1"), events)   // run 2 is parked until run 1 has finished its finally
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf("acquire1", "release1", "acquire2"), events)
        assertTrue(c.state.value.scanning)
    }
}
