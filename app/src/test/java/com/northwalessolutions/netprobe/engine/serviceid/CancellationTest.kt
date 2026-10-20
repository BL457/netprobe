package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.discovery.HttpTitleFetcher
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * spec 9.1 test 14: Stop must not wait for a socket timeout; a blocking read ignores interrupts, so the socket is closed instead
 * (Gruff m8). The caller returning fast is not enough (suspendCancellableCoroutine resumes at once whatever close does), so each
 * case also proves the server sees the connection closed within 300 ms: the blocked IO thread was really released (plan review G7).
 */
class CancellationTest {
    private val lo = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val servers = mutableListOf<ServerSocket>()
    private val accepted = mutableListOf<Socket>()
    @After fun tearDown() { synchronized(accepted) { accepted.forEach { runCatching { it.close() } } }; servers.forEach { runCatching { it.close() } } }

    /** Accepts every connection and then says nothing, ever. */
    private fun silent(): Int {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { while (true) { val c = s.accept(); synchronized(accepted) { accepted += c } } } }
        return s.localPort
    }
    private fun assertCancelsWithin(limitMs: Long, call: suspend () -> Any?) = runBlocking {
        val job = launch(Dispatchers.IO) { call() }
        delay(BLOCK_MS)                                   // the call is now blocked in a read or a handshake
        val peer = synchronized(accepted) { accepted.lastOrNull() } ?: return@runBlocking fail("the probe never connected")
        val t0 = System.nanoTime()
        job.cancelAndJoin()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("cancel took $ms ms", ms <= limitMs)
        assertPeerClosedWithin(peer, limitMs)
    }
    /** Drains what the probe sent (a ClientHello, a GET) and expects end of stream or a reset before the deadline. */
    private fun assertPeerClosedWithin(peer: Socket, limitMs: Long) {
        peer.soTimeout = POLL_MS
        val input = peer.getInputStream(); val buf = ByteArray(4096)
        val deadline = System.nanoTime() + limitMs * 1_000_000
        while (System.nanoTime() < deadline) {
            try { if (input.read(buf) < 0) return } catch (_: SocketTimeoutException) { continue } catch (_: SocketException) { return }
        }
        fail("the probe's socket was still open $limitMs ms after the cancel: the blocked IO thread was not released")
    }

    @Test fun `banner read stops within 300 ms`() { val p = silent(); assertCancelsWithin(LIMIT_MS) { BannerReader(timeoutMs = LONG_MS).read(lo, p) } }
    @Test fun `tls peek stops within 300 ms`() { val p = silent(); assertCancelsWithin(LIMIT_MS) { TlsPeek().certInfo(lo, p, timeoutMs = LONG_MS) } }
    @Test fun `http fetch stops within 300 ms`() { val p = silent(); assertCancelsWithin(LIMIT_MS) { HttpTitleFetcher(timeoutMs = LONG_MS).fetch(lo, p, tls = false) } }

    private companion object { const val LIMIT_MS = 300L; const val BLOCK_MS = 300L; const val LONG_MS = 10_000; const val POLL_MS = 20 }
}
