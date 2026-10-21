package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.common.currentBinder
import com.northwalessolutions.netprobe.engine.discovery.KnownRanges
import com.northwalessolutions.netprobe.netenv.HomeRange
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.*

/** 3C spec 8.1 item 21, D20, ruling 4 (Review Focus 3). */
class ManualRouteTest {
    private object FakeBinder : SocketBinder {
        override fun bind(socket: Socket) {}
        override fun bind(socket: DatagramSocket) {}
        override fun openConnection(url: URL): URLConnection = url.openConnection(Proxy.NO_PROXY)
    }
    private fun ip(s: String) = Ipv4.parse(s)!!
    private val wifi = NetworkEnv(ip("10.0.3.20"), 24, ip("10.0.3.1"), emptyList(), "Home", null, Transport.WIFI)
    private val ethernet = wifi.copy(transport = Transport.ETHERNET)
    private val known = KnownRanges.of(SubnetMath.of(ip("10.0.3.20"), 24), emptyList(), listOf(TestHosts.host(ip = "10.0.4.20")))

    @Test fun `a home range target and a known slash 24 target go over Wi-Fi or Ethernet`() {
        for (t in listOf("10.0.3.5", "10.0.4.99")) {
            assertEquals(t, ToolRoute.WIFI, ManualRoute.of(ip(t), known, wifi))
            assertEquals(t, ToolRoute.ETHERNET, ManualRoute.of(ip(t), known, ethernet))
        }
    }
    @Test fun `targets outside every known range take the system route`() {
        for (t in listOf("10.20.0.5", "8.8.8.8", "100.64.1.1", "169.254.10.10")) assertEquals(t, ToolRoute.OTHER, ManualRoute.of(ip(t), known, wifi))
    }
    @Test fun `Aggressive scan makes no difference for a target outside every known range`() {
        for (aggressive in listOf(false, true)) {
            val home = HomeRange.of(ip("10.0.3.20"), 24, 22, aggressive).subnet
            assertEquals("aggressive $aggressive", ToolRoute.OTHER, ManualRoute.of(ip("10.20.0.5"), KnownRanges.of(home, emptyList(), emptyList()), wifi))
        }
    }
    @Test fun `bind uses the binder for Wi-Fi and Ethernet and never asks for one for OTHER`() = runBlocking {
        var lookups = 0
        val binderFor: (NetworkEnv) -> SocketBinder? = { lookups++; FakeBinder }
        assertSame(FakeBinder, ManualRoute.bind(ToolRoute.WIFI, wifi, binderFor) { currentBinder() })
        assertSame(FakeBinder, ManualRoute.bind(ToolRoute.ETHERNET, ethernet, binderFor) { currentBinder() })
        assertNull(ManualRoute.bind(ToolRoute.OTHER, wifi, binderFor) { currentBinder() })
        assertEquals(2, lookups)
    }
    @Test fun `no binder for a Wi-Fi route is a bind failure, never an unbound run`() = runBlocking {
        var ran = false
        try { ManualRoute.bind(ToolRoute.WIFI, wifi, { null }) { ran = true }; fail("expected BindFailedException") } catch (_: BindFailedException) { }
        assertFalse("the block never ran", ran)
    }
    @Test fun `R4 on, a private target outside the known ranges goes over the LAN when it has no internet and no VPN is the default`() {
        val offline = RouteContext(lanHasInternet = false, defaultIsVpn = false)
        for (t in listOf("10.20.0.5", "100.64.1.1", "169.254.10.10")) assertEquals(t, ToolRoute.WIFI, ManualRoute.of(ip(t), known, wifi, offline, privateOverLan = true))
        assertEquals(ToolRoute.ETHERNET, ManualRoute.of(ip("10.20.0.5"), known, ethernet, offline, privateOverLan = true))
        assertEquals("a public target stays unbound", ToolRoute.OTHER, ManualRoute.of(ip("8.8.8.8"), known, wifi, offline, privateOverLan = true))
        assertEquals("a VPN as the default carries it", ToolRoute.OTHER, ManualRoute.of(ip("10.20.0.5"), known, wifi, RouteContext(false, defaultIsVpn = true), privateOverLan = true))
        assertEquals("a LAN with internet: ruling 4", ToolRoute.OTHER, ManualRoute.of(ip("10.20.0.5"), known, wifi, RouteContext.CONNECTED, privateOverLan = true))
    }
    @Test fun `R4 off is ruling 4 as written`() {
        assertEquals(ToolRoute.OTHER, ManualRoute.of(ip("10.20.0.5"), known, wifi, RouteContext(lanHasInternet = false, defaultIsVpn = false), privateOverLan = false))
        assertTrue("the shipped default is the controller's recommendation", ManualRoute.PRIVATE_TARGETS_OVER_LAN_WITHOUT_INTERNET)
    }
    @Test fun `route names round trip and anything else parses to null`() {
        ToolRoute.entries.forEach { assertEquals(it, ToolRoute.parse(it.name)) }
        assertNull(ToolRoute.parse(null)); assertNull(ToolRoute.parse("VPN")); assertNull(ToolRoute.parse("wifi"))
        assertTrue(ManualRoute.needsBinder(ToolRoute.WIFI)); assertTrue(ManualRoute.needsBinder(ToolRoute.ETHERNET)); assertFalse(ManualRoute.needsBinder(ToolRoute.OTHER))
    }
}
