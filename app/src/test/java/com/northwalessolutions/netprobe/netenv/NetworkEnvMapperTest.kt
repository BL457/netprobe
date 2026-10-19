package com.northwalessolutions.netprobe.netenv

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class NetworkEnvMapperTest {
    private fun a(s: String) = InetAddress.getByName(s)
    private val wifi = LinkSnapshot(
        addresses = listOf(a("fe80::1") to 64, a("192.168.1.23") to 24),
        routes = listOf(a("192.168.1.1") to true, null to false),
        dns = listOf(a("192.168.1.1"), a("1.1.1.1")),
        isWifi = true, isEthernet = false, isVpn = false, ssid = "Home", bssid = "aa:bb:cc:dd:ee:ff")

    @Test fun `wifi snapshot maps to env with ipv4 and gateway`() {
        val env = NetworkEnvMapper.map(wifi)!!
        assertEquals("192.168.1.23", env.interfaceIp.hostAddress)
        assertEquals(24, env.prefixLength)
        assertEquals("192.168.1.1", env.gateway?.hostAddress)
        assertEquals(Transport.WIFI, env.transport)
        assertEquals("Home", env.ssid)
    }
    @Test fun `unknown ssid placeholder becomes null`() {
        val env = NetworkEnvMapper.map(wifi.copy(ssid = "<unknown ssid>", bssid = "02:00:00:00:00:00"))!!
        assertNull(env.ssid); assertNull(env.bssid)
    }
    @Test fun `ethernet maps`() {
        val env = NetworkEnvMapper.map(wifi.copy(isWifi = false, isEthernet = true, ssid = null, bssid = null))!!
        assertEquals(Transport.ETHERNET, env.transport)
    }
    @Test fun `ethernet never carries wifi ids`() {
        val env = NetworkEnvMapper.map(wifi.copy(isWifi = false, isEthernet = true))!!
        assertNull(env.ssid); assertNull(env.bssid)
    }
    @Test fun `no ipv4 address yields null`() {
        assertNull(NetworkEnvMapper.map(wifi.copy(addresses = listOf(a("fe80::1") to 64))))
    }
    @Test fun `vpn or cellular only yields null`() {
        assertNull(NetworkEnvMapper.map(wifi.copy(isVpn = true)))
        assertNull(NetworkEnvMapper.map(wifi.copy(isWifi = false, isEthernet = false)))
    }
    @Test fun `missing default route gives null gateway but still an env`() {
        val env = NetworkEnvMapper.map(wifi.copy(routes = emptyList()))!!
        assertNull(env.gateway)
    }
}
