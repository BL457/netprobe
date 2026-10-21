package com.northwalessolutions.netprobe.engine.portscan

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** Spec 3.3 and 9.1 item 8: the gate runs before each probe and onProgress reports every step. Loopback only. */
class PortScanEngineGateTest {
    private val servers = mutableListOf<ServerSocket>()
    private fun listen(): Int { val s = ServerSocket(0, 50, InetAddress.getLoopbackAddress()); servers += s; return s.localPort }
    private fun freePort(): Int { val s = ServerSocket(0); val p = s.localPort; s.close(); return p }
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }

    @Test fun `no probe is issued while the gate is closed and probing resumes when it opens`() = runBlocking {
        val ports = listOf(listen(), freePort(), freePort(), freePort())
        val open = CompletableDeferred<Unit>()
        val results = CopyOnWriteArrayList<PortResult>()
        val job = launch(Dispatchers.Default) {
            PortScanEngine().scan(InetAddress.getLoopbackAddress(), ScanProfile.Custom(ports), gate = { open.await() }).collect { results += it }
        }
        delay(300)
        assertEquals("a closed gate issues no probe", 0, results.size)
        open.complete(Unit); job.join()
        assertEquals(4, results.size)
    }
    @Test fun `onProgress reports the start and every finished probe`() = runBlocking {
        val seen = CopyOnWriteArrayList<ScanProgress>()
        val ports = listOf(listen(), freePort(), freePort())
        PortScanEngine().scan(InetAddress.getLoopbackAddress(), ScanProfile.Custom(ports), onProgress = { seen += it }).collect { }
        assertEquals(ScanProgress(0, 3, 0, 0), seen.first())
        assertEquals(ScanProgress(3, 3, 1, 0), seen.last())
        assertEquals("start plus one per port", 4, seen.size)
        assertTrue(seen.zipWithNext().all { (a, b) -> b.done >= a.done })
    }
}
