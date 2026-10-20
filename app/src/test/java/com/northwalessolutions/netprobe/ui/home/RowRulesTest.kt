package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.netenv.SubnetMath
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class RowRulesTest {
    private val scanned = SubnetMath.of(InetAddress.getByAddress(byteArrayOf(10, 0, 3, 242.toByte())) as Inet4Address, 24)

    @Test fun `host inside the scanned subnet is not flagged`() { assertNull(RowRules.otherSubnet("10.0.3.7", scanned)) }
    @Test fun `host outside the scanned subnet reports its own slash 24`() { assertEquals("10.0.4.0/24", RowRules.otherSubnet("10.0.4.20", scanned)) }
    @Test fun `garbage ip is not flagged and never resolved`() {
        // parseIpv4 is a pure literal parser: none of these may trigger a name lookup.
        for (bad in listOf("not-an-ip", "10.0.3", "10.0.3.256", "1.2.3.4.5", "", "a.b.c.d")) { assertNull(bad, RowRules.parseIpv4(bad)); assertNull(RowRules.otherSubnet(bad, scanned)) }
        assertEquals("10.0.3.7", RowRules.parseIpv4("10.0.3.7")!!.hostAddress)
    }
    private fun host(ip: String, customName: String? = null, vendor: String? = "Google", type: DeviceType = DeviceType.MEDIA) = HostEntity(
        networkId = 1, ip = ip, mac = null, reverseDnsName = null, mdnsName = null, netbiosName = null, upnpName = null, upnpManufacturer = null, upnpModel = null, vendor = null,
        deviceType = type, customName = customName, identVendor = vendor, identModel = null, identOs = null, identConfidence = 50, identReasons = emptyList(),
        snmpSysDescr = null, httpTitle = null, mdnsTxtJson = null, status = HostStatus.UNMARKED, note = "", isGateway = false, firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = 0)
    @Test fun `own address is labelled at row time whatever the stored identity`() {
        val r = RowRules.row(host("192.168.1.50"), TestEnvs.home, 22, 0, false)
        assertEquals("This phone", r.displayName); assertEquals("Android device", r.identityLine); assertEquals(DeviceType.PHONE, r.type)
        assertEquals("My Pixel", RowRules.row(host("192.168.1.50", customName = "My Pixel"), TestEnvs.home, 22, 0, false).displayName)
        assertTrue(RowRules.isOwn("192.168.1.50", TestEnvs.home)); assertFalse(RowRules.isOwn("192.168.1.51", TestEnvs.home)); assertFalse(RowRules.isOwn("192.168.1.50", null))
    }
    @Test fun `another address keeps its stored identity`() {
        val r = RowRules.row(host("192.168.1.51"), TestEnvs.home, 22, 3, false)
        assertEquals("192.168.1.51", r.displayName); assertEquals("Google", r.identityLine); assertEquals(DeviceType.MEDIA, r.type)
        // After a DHCP move the phone's old address is just another host.
        assertEquals(DeviceType.MEDIA, RowRules.row(host("192.168.1.50"), TestEnvs.home.copy(interfaceIp = RowRules.parseIpv4("192.168.1.60")!!), 22, 0, false).type)
    }
}

class NetworkDetailLineTest {
    private fun v4(a: Int, b: Int, c: Int, d: Int) = InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
    @Test fun `shows the first two dns servers after the gateway`() {
        val env = TestEnvs.home.copy(dnsServers = listOf(v4(10, 0, 3, 1), v4(1, 1, 1, 1), v4(8, 8, 8, 8)))
        assertEquals("192.168.1.50/24  gateway 192.168.1.1  dns 10.0.3.1, 1.1.1.1", networkDetailLine(env))
    }
    @Test fun `no dns servers leaves the line as it was`() {
        assertEquals("192.168.1.50/24  gateway 192.168.1.1", networkDetailLine(TestEnvs.home))
        assertEquals("192.168.1.50/24  gateway none", networkDetailLine(TestEnvs.home.copy(gateway = null)))
    }
}
