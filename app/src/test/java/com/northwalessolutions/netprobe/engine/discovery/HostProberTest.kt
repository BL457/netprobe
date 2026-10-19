package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket

class HostProberTest {
    private val servers = mutableListOf<ServerSocket>()
    private fun listen(): Int { val s = ServerSocket(0, 50, InetAddress.getLoopbackAddress()); servers += s; return s.localPort }
    private fun freePort(): Int { val s = ServerSocket(0); val p = s.localPort; s.close(); return p }
    private val lo = InetAddress.getLoopbackAddress() as Inet4Address
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }

    @Test fun `open port means alive and is recorded`() = runBlocking {
        val open = listen()
        val l = HostProber(ports = listOf(open, freePort()), timeoutMs = 500).probe(lo)
        assertTrue(l.alive); assertEquals(setOf(open), l.openPorts); assertNotNull(l.rttMs)
    }
    @Test fun `refused port alone means alive with no open ports`() = runBlocking {
        val l = HostProber(ports = listOf(freePort()), timeoutMs = 500).probe(lo)
        assertTrue(l.alive); assertTrue(l.openPorts.isEmpty())
    }
    @Test fun `all timeouts means not alive`() = runBlocking {
        val l = HostProber(ports = listOf(80, 443), timeoutMs = 300).probe(InetAddress.getByName("192.0.2.1") as Inet4Address)
        assertFalse(l.alive)
    }
    @Test fun `stops at first definitive answer`() = runBlocking {
        val t0 = System.currentTimeMillis()
        HostProber(ports = listOf(freePort(), freePort(), freePort()), timeoutMs = 2000).probe(lo)
        assertTrue(System.currentTimeMillis() - t0 < 1500)
    }
}
