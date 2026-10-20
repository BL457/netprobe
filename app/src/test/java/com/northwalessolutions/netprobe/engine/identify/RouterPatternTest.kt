package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** spec 9.1 test 20 (Gruff m10). */
class RouterPatternTest {
    private val routerRules = IdentifyRules.load(javaClass.getResource("/identify_rules.json")!!.readText()).filter { it.id in setOf("p-router-title", "p-isp-hub") }
    private val ip = InetAddress.getByName("10.0.3.1") as Inet4Address
    private fun title(t: String) = DeviceIdentifier(routerRules).identify(
        HostEvidence(DiscoveredHost(ip), setOf(80), mapOf(80 to ServiceInfo("HTTP", confidence = Confidence.CONFIRMED, title = t)), null))

    @Test fun `ordinary words that contain a vendor name match no router rule`() {
        assertEquals(2, routerRules.size)
        for (t in listOf("Attendance", "Pegasus", "Aztec")) assertEquals(t, DeviceIdentity.UNKNOWN, title(t))
        assertNotEquals(DeviceType.ROUTER, title("ASUSTOR Data Master").type)
    }
    @Test fun `a vendor in any case is spelled the canonical way`() {
        val r = title("netgear router"); assertEquals("NETGEAR", r.vendor); assertEquals(DeviceType.ROUTER, r.type)
        assertEquals("TP-Link", title("TP-LINK Archer AX55").vendor); assertEquals("ASUS", title("Asus RT-AX86U").vendor)
        assertEquals("Hub Manager", title("Hub Manager").model)
    }
}
