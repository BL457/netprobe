package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket

class SweepTest {
    @Test fun `emits only alive hosts and tracks progress`() = runBlocking {
        val lo = InetAddress.getLoopbackAddress() as Inet4Address
        val dead = InetAddress.getByName("192.0.2.1") as Inet4Address
        val sweep = Sweep(HostProber(ports = listOf(ServerSocket(0).also { it.close() }.localPort), timeoutMs = 300), concurrency = 8)
        val out = sweep.run(sequenceOf(lo, dead, dead)).toList()
        assertEquals(listOf(lo), out.map { it.ip })
        assertEquals(3 to 3, sweep.progress.value)
    }
    @Test fun `runAll emits every result including dead hosts`() = runBlocking {
        val lo = InetAddress.getLoopbackAddress() as Inet4Address
        val dead = InetAddress.getByName("192.0.2.1") as Inet4Address
        ServerSocket(0).use { server ->
            val sweep = Sweep(HostProber(ports = listOf(server.localPort), timeoutMs = 300), concurrency = 8)
            val out = sweep.runAll(sequenceOf(lo, dead)).toList()
            assertEquals(setOf(lo to true, dead to false), out.map { it.ip to it.alive }.toSet())
            assertEquals(2 to 2, sweep.progress.value)
        }
    }
}
