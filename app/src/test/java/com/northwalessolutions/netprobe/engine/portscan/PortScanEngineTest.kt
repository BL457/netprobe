package com.northwalessolutions.netprobe.engine.portscan

import app.cash.turbine.test
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class PortScanEngineTest {
    private val servers = mutableListOf<ServerSocket>()
    private fun listen(): Int { val s = ServerSocket(0, 50, InetAddress.getLoopbackAddress()); servers += s; return s.localPort }
    private fun freePort(): Int { val s = ServerSocket(0); val p = s.localPort; s.close(); return p }
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }

    @Test fun `open and closed ports are classified`() = runBlocking {
        val open = listen(); val closed = freePort()
        val results = PortScanEngine().scan(InetAddress.getLoopbackAddress(), ScanProfile.Custom(listOf(open, closed))).toList()
        assertEquals(PortState.OPEN, results.first { it.port == open }.state)
        assertEquals(PortState.CLOSED, results.first { it.port == closed }.state)
        assertNotNull(results.first { it.port == open }.rttMs)
    }
    @Test fun `unroutable address times out as FILTERED within the timeout budget`() = runBlocking {
        val t0 = System.currentTimeMillis()
        val results = PortScanEngine().scan(InetAddress.getByName("192.0.2.1"), ScanProfile.Custom(listOf(80, 443)),
            ScanOptions(concurrency = 2, connectTimeoutMs = 300)).toList()
        assertTrue(results.all { it.state == PortState.FILTERED })
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 2500)
    }
    @Test fun `progress counts every port once and counts opens`() = runBlocking {
        val open = listen(); val engine = PortScanEngine()
        engine.scan(InetAddress.getLoopbackAddress(), ScanProfile.Custom(listOf(open, freePort(), freePort()))).toList()
        assertEquals(ScanProgress(done = 3, total = 3, open = 1, errors = 0), engine.progress.value)
    }
    @Test fun `cancellation stops the flow quickly`() = runBlocking {
        val engine = PortScanEngine()
        val job = launch { engine.scan(InetAddress.getByName("192.0.2.1"), ScanProfile.Full, ScanOptions(concurrency = 64, connectTimeoutMs = 2000)).collect { } }
        delay(300)
        val t0 = System.currentTimeMillis(); job.cancelAndJoin()
        assertTrue("cancel took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 1000)
    }
    @Test fun `results arrive progressively not in one batch`() = runBlocking {
        val open = listen()
        PortScanEngine().scan(InetAddress.getLoopbackAddress(), ScanProfile.Custom(listOf(open) + (1..40).map { freePort() })).test {
            awaitItem(); cancelAndIgnoreRemainingEvents()
        }
    }
}
