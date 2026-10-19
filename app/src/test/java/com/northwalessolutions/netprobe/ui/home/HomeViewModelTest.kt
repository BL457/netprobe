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

    // Final review C1/I2: identity is gateway + prefix; a BSSID change (roaming) is the same network.
    @Test fun `roaming to another access point does not stop the scan`() = runTest {
        val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home.copy(ssid = "Home", bssid = "aa:bb:cc:dd:ee:01"))
        val c = ScanController(scope = backgroundScope, env = env, runDiscovery = { kotlinx.coroutines.awaitCancellation() })
        c.start(); runCurrent()
        env.value = TestEnvs.home.copy(ssid = "Home", bssid = "aa:bb:cc:dd:ee:02"); runCurrent()
        env.value = TestEnvs.home; runCurrent()   // Location turned off: no SSID or BSSID at all
        assertTrue(c.state.value.scanning); assertNull(c.state.value.message)
        env.value = TestEnvs.home.copy(prefixLength = 23); runCurrent()
        assertFalse(c.state.value.scanning); assertEquals("Network changed, scan stopped", c.state.value.message)
    }
    // Final review I2: the Detail port scan runs its own work through a controller and stops on a Wi-Fi switch.
    @Test fun `a detail port scan is cancelled when wi-fi switches`() = runTest {
        val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home)
        val probed = mutableListOf<Int>()
        var cancelled = false
        val c = ScanController(scope = backgroundScope, env = env, runDiscovery = { fail("default work must not run") })
        c.start { for (port in 1..1000) { probed += port; kotlinx.coroutines.delay(10) } }
        runCurrent(); advanceTimeBy(55); runCurrent()
        val before = probed.size
        assertTrue(c.state.value.scanning); assertTrue(before in 1..10)
        env.value = TestEnvs.office; runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals("no probe after the switch", before, probed.size)
        assertFalse(c.state.value.scanning); assertEquals("Network changed, scan stopped", c.state.value.message)
        c.start { try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true } }
        runCurrent(); assertTrue(c.state.value.scanning)
        env.value = null; runCurrent()
        assertTrue(cancelled); assertFalse(c.state.value.scanning)
    }
    // Final review I2: Quick scan all can be stopped, is stopped by a network loss, and always clears its running flag.
    @Test fun `quick scan all stops on request and on network loss`() = runTest {
        val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home)
        val scanned = mutableListOf<String>(); var cleanedUp = 0
        val hosts = listOf("192.168.1.2", "192.168.1.3", "192.168.1.4")
        val quick = ScanController(scope = backgroundScope, env = env, runDiscovery = {
            try { for (h in hosts) { scanned += h; kotlinx.coroutines.delay(1_000) } } finally { cleanedUp++ }
        })
        quick.start(); runCurrent(); assertTrue(quick.state.value.scanning)
        quick.stop(); runCurrent()
        assertFalse(quick.state.value.scanning); assertNull(quick.state.value.message); assertEquals(1, cleanedUp)
        advanceTimeBy(5_000); runCurrent(); assertEquals(listOf("192.168.1.2"), scanned)
        quick.start(); runCurrent(); assertTrue(quick.isRunning)
        env.value = null; runCurrent()
        assertFalse(quick.state.value.scanning); assertFalse(quick.isRunning); assertEquals(2, cleanedUp)
        assertEquals("Network changed, scan stopped", quick.state.value.message)
        quick.start(); runCurrent()
        assertFalse(quick.state.value.scanning); assertEquals("Connect to Wi-Fi or Ethernet to scan", quick.state.value.message)
    }
}
