package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.netenv.SubnetMath
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class RowRulesTest {
    private val scanned = SubnetMath.of(InetAddress.getByName("10.0.3.242") as Inet4Address, 24)

    @Test fun `host inside the scanned subnet is not flagged`() { assertNull(RowRules.otherSubnet("10.0.3.7", scanned)) }
    @Test fun `host outside the scanned subnet reports its own slash 24`() { assertEquals("10.0.4.0/24", RowRules.otherSubnet("10.0.4.20", scanned)) }
    @Test fun `garbage ip is not flagged`() { assertNull(RowRules.otherSubnet("not-an-ip", scanned)) }
    @Test fun `own address gets the phone identity`() {
        val i = RowRules.ownIdentity()
        assertEquals("This phone", i.vendor); assertEquals(DeviceType.PHONE, i.type); assertEquals(100, i.confidence); assertEquals(listOf("own address"), i.reasons)
        assertTrue(RowRules.isOwn("192.168.1.50", TestEnvs.home)); assertFalse(RowRules.isOwn("192.168.1.51", TestEnvs.home)); assertFalse(RowRules.isOwn("192.168.1.50", null))
    }
    @Test fun `secondary line appends the other subnet`() {
        val r = HostRow(1, "10.0.4.20", "Hue Bridge - 317AA9", "Philips Hue Bridge", DeviceType.IOT, 0, HostStatus.UNMARKED, false, false, null, "10.0.4.0/24")
        assertTrue(r.offSubnet); assertEquals("10.0.4.20  ·  Philips Hue Bridge, other subnet 10.0.4.0/24", rowSecondaryLine(r))
        assertEquals("10.0.4.20  ·  Philips Hue Bridge", rowSecondaryLine(r.copy(otherSubnet = null)))
    }
}
