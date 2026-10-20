package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.common.currentBinder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.*

/** Final review I2: off-subnet port scans are bound to the Wi-Fi network when extended discovery is on; local rows never are. */
class ScanBindingTest {
    private object FakeBinder : SocketBinder {
        override fun bind(socket: Socket) {}
        override fun bind(socket: DatagramSocket) {}
        override fun openConnection(url: URL): URLConnection = url.openConnection(Proxy.NO_PROXY)
    }
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private val env = TestEnvs.home   // 192.168.1.50/24
    private var lookups = 0
    private val binders: (com.northwalessolutions.netprobe.netenv.NetworkEnv) -> SocketBinder? = { lookups++; FakeBinder }

    private suspend fun seen(host: String, ext: Boolean, b: (com.northwalessolutions.netprobe.netenv.NetworkEnv) -> SocketBinder? = binders): SocketBinder? =
        ScanBinding.run(ip(host), env, maxPrefix = 22, extEnabled = ext, binderFor = b) { currentBinder() }

    @Test fun `an off-subnet row's scan sees the binder`() = runBlocking {
        assertSame(FakeBinder, seen("10.0.4.7", ext = true)); assertEquals(1, lookups)
    }
    @Test fun `a local row's scan does not`() = runBlocking {
        assertNull(seen("192.168.1.20", ext = true)); assertEquals(0, lookups)
    }
    @Test fun `with extended discovery off nothing is bound, as in the MVP`() = runBlocking {
        assertNull(seen("10.0.4.7", ext = false)); assertEquals(0, lookups)
    }
    @Test fun `an off-subnet row with no Wi-Fi network to bind fails the scan instead of leaving unbound`() = runBlocking {
        try { seen("10.0.4.7", ext = true, b = { null }); fail("expected BindFailedException") } catch (_: BindFailedException) { }
    }
}
