package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.SettingsDefaults
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.common.currentBinder
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.*

/** 3C spec 8.1 item 4, Review Focus 4: a run uses only the settings it started with. */
class RunSnapshotTest {
    private object FakeBinder : SocketBinder {
        override fun bind(socket: Socket) {}
        override fun bind(socket: DatagramSocket) {}
        override fun openConnection(url: URL): URLConnection = url.openConnection(Proxy.NO_PROXY)
    }
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private val env16 = NetworkEnv(ip("172.20.9.40"), 16, ip("172.20.0.1"), emptyList(), null, null, Transport.WIFI)
    private val host = TestHosts.host(ip = "172.20.9.7")

    @Test fun `a run started with Aggressive off keeps its binding, home range and grouping after a mid run change`() = runBlocking {
        val started = RunSettings.of(SettingsState(), emptyList())                         // Aggressive scan off when Scan was tapped
        val switchedOn = SettingsState(extEnabled = true, maxPrefix = 20)                   // the Director turns it on mid-run
        for (live in listOf(switchedOn, SettingsDefaults.reset(switchedOn))) {               // and then resets Settings mid-run
            val eff = RunSettings.effective(started, live, emptyList())
            assertSame(started, eff)
            assertEquals("172.20.9.0/24", eff.home(env16).toString())
            assertNull("still unbound", ScanBinding.forRun(ip("172.20.30.5"), env16, eff, { FakeBinder }) { currentBinder() })
            val row = RowRules.row(host, env16, eff.maxPrefix, 0, false, manualRanges = eff.manual, aggressive = eff.aggressive)
            assertEquals("172.20.9.0/24" to false, row.subnet to row.offSubnet)
        }
        val next = RunSettings.effective(null, switchedOn, emptyList())                     // the next run reads the new values
        assertEquals("172.20.0.0/20", next.home(env16).toString())
        assertSame(FakeBinder, ScanBinding.forRun(ip("10.9.0.5"), env16, next, { FakeBinder }) { currentBinder() })
        assertEquals("172.20.0.0/20", RowRules.row(host, env16, next.maxPrefix, 0, false, aggressive = next.aggressive).subnet)
    }
    @Test fun `after the run the live settings regroup at once and the hero follows`() {   // plan review G7
        val started = RunSettings.of(SettingsState(), emptyList())
        val live = SettingsState(extEnabled = true, maxPrefix = 20)
        assertSame("while the scan runs", started, RunSettings.forDisplay(started, scanning = true, live = live, liveManual = emptyList()))
        val after = RunSettings.forDisplay(started, scanning = false, live = live, liveManual = emptyList())
        assertEquals("the hero's range", "172.20.0.0/20", after.home(env16).toString())
        assertEquals("172.20.0.0/20", RowRules.row(host, env16, after.maxPrefix, 0, false, aggressive = after.aggressive).subnet)
    }
    @Test fun `a network change drops the snapshot, the same network keeps it`() {
        val mem = RunMemory(finishedAt = 500L, swept = emptyList(), progress = emptyList(), settings = RunSettings(true, 22, emptyList()))
        assertNull(mem.afterNetworkChange(1L, 2L).settings)
        assertEquals(mem, mem.afterNetworkChange(1L, 1L))
    }
}
