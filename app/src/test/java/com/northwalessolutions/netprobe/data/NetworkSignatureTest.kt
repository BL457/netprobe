package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest

class NetworkSignatureTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private val base = NetworkEnv(ip("192.168.1.5"), 24, ip("192.168.1.1"), emptyList(), null, null, Transport.WIFI)
    private val mac = "e0:63:da:26:f1:37"
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun `same gateway and prefix share a signature`() { assertEquals(NetworkSignature.of(base, mac), NetworkSignature.of(base.copy(interfaceIp = ip("192.168.1.99")), mac)) }
    @Test fun `bssid and ssid do not affect the signature`() {
        val s = NetworkSignature.of(base, mac)
        assertEquals(s, NetworkSignature.of(base.copy(bssid = "aa:bb:cc:dd:ee:01", ssid = "Home"), mac))
        assertEquals(s, NetworkSignature.of(base.copy(bssid = "aa:bb:cc:dd:ee:02"), mac))
    }
    @Test fun `gateway mac separates otherwise identical networks`() { assertNotEquals(NetworkSignature.of(base, mac), NetworkSignature.of(base, "00:11:22:33:44:55")) }
    @Test fun `mac case does not matter`() { assertEquals(NetworkSignature.of(base, mac), NetworkSignature.of(base, mac.uppercase())) }
    @Test fun `null mac falls back to gateway and prefix`() {
        assertEquals(sha("192.168.1.1/24"), NetworkSignature.of(base, null))
        assertEquals(sha("$mac" + "192.168.1.1/24"), NetworkSignature.of(base, mac))
        assertNotEquals(NetworkSignature.of(base, null), NetworkSignature.of(base.copy(prefixLength = 23), null))
    }
    @Test fun `identity key is gateway and prefix only`() {
        assertEquals("192.168.1.1/24", NetworkSignature.identityKey(base.copy(bssid = "aa:bb:cc:dd:ee:01", ssid = "x")))
        assertEquals("/24", NetworkSignature.identityKey(base.copy(gateway = null)))
    }
    @Test fun `is 64 hex chars`() { assertTrue(Regex("^[0-9a-f]{64}$").matches(NetworkSignature.of(base, mac))) }
}
