package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.common.currentBinder
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.*

/** Phase 2 final review I2 and 3C spec 8.1 item 5: rows outside the home range are bound to Wi-Fi when the run binds outside; local rows never are. */
class ScanBindingTest {
    private object FakeBinder : SocketBinder {
        override fun bind(socket: Socket) {}
        override fun bind(socket: DatagramSocket) {}
        override fun openConnection(url: URL): URLConnection = url.openConnection(Proxy.NO_PROXY)
    }
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private val env = TestEnvs.home   // 192.168.1.50/24
    private val home = SubnetMath.of(ip("192.168.1.50"), 24)
    private var lookups = 0
    private val binders: (NetworkEnv) -> SocketBinder? = { lookups++; FakeBinder }

    private suspend fun seen(host: String, bindOutside: Boolean, b: (NetworkEnv) -> SocketBinder? = binders): SocketBinder? =
        ScanBinding.run(ip(host), env, home, bindOutside, b) { currentBinder() }

    @Test fun `an off-subnet row's scan sees the binder`() = runBlocking {
        assertSame(FakeBinder, seen("10.0.4.7", bindOutside = true)); assertEquals(1, lookups)
    }
    @Test fun `a local row's scan does not`() = runBlocking {
        assertNull(seen("192.168.1.20", bindOutside = true)); assertEquals(0, lookups)
    }
    @Test fun `bindOutside false is unbound for any target`() = runBlocking {
        for (h in listOf("10.0.4.7", "192.168.1.20", "8.8.8.8")) assertNull(h, seen(h, bindOutside = false))
        assertEquals(0, lookups)
    }
    @Test fun `bindOutside with no Wi-Fi network to bind fails the scan instead of leaving it unbound`() = runBlocking {
        try { seen("10.0.4.7", bindOutside = true, b = { null }); fail("expected BindFailedException") } catch (_: BindFailedException) { }
    }
    @Test fun `with no env the block runs unbound`() = runBlocking {
        assertNull(ScanBinding.run(ip("10.0.4.7"), null, null, true, binders) { currentBinder() }); assertEquals(0, lookups)
    }
    @Test fun `aggressive off with one manual range binds a target outside the home range`() = runBlocking {
        val withManual = RunSettings(aggressive = false, maxPrefix = 22, manual = listOf(CidrParser.subnetOf("10.0.4.0/24")!!))
        assertSame(FakeBinder, ScanBinding.forRun(ip("10.0.4.7"), env, withManual, binders) { currentBinder() })
        assertNull(ScanBinding.forRun(ip("192.168.1.20"), env, withManual, binders) { currentBinder() })
        assertNull("no manual ranges and Aggressive off: the MVP, unbound", ScanBinding.forRun(ip("10.0.4.7"), env, RunSettings(false, 22, emptyList()), binders) { currentBinder() })
    }
    @Test fun `aggressive on with a wide mask binds outside the limit block only`() = runBlocking {
        val env16 = NetworkEnv(ip("172.20.9.40"), 16, ip("172.20.0.1"), emptyList(), null, null, Transport.WIFI)
        val on = RunSettings(aggressive = true, maxPrefix = 22, manual = emptyList())
        assertNull(ScanBinding.forRun(ip("172.20.10.7"), env16, on, binders) { currentBinder() })        // inside 172.20.8.0/22
        assertSame(FakeBinder, ScanBinding.forRun(ip("172.20.30.5"), env16, on, binders) { currentBinder() })
    }
}
