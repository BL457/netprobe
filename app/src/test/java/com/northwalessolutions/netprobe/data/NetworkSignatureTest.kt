package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class NetworkSignatureTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private val base = NetworkEnv(ip("192.168.1.5"), 24, ip("192.168.1.1"), emptyList(), null, null, Transport.WIFI)
    @Test fun `same gateway and prefix without ids share a signature`() { assertEquals(NetworkSignature.of(base), NetworkSignature.of(base.copy(interfaceIp = ip("192.168.1.99")))) }
    @Test fun `bssid separates otherwise identical networks`() { assertNotEquals(NetworkSignature.of(base.copy(bssid = "aa:bb:cc:dd:ee:01")), NetworkSignature.of(base.copy(bssid = "aa:bb:cc:dd:ee:02"))) }
    @Test fun `is 64 hex chars`() { assertTrue(Regex("^[0-9a-f]{64}$").matches(NetworkSignature.of(base))) }
}
