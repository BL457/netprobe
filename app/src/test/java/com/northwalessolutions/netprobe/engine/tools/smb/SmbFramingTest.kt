package com.northwalessolutions.netprobe.engine.tools.smb

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Direct-TCP framing (MS-SMB2 2.1): a zero byte, a 24-bit big-endian length, the message; replies over 64 KB are refused. */
class SmbFramingTest {
    private val lo = InetAddress.getByName("127.0.0.1")
    private val servers = mutableListOf<ServerSocket>()
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun `frame bytes`() {
        assertEquals("00000003010203", hex(SmbFraming.frame(byteArrayOf(1, 2, 3))))
        assertEquals("00010000", hex(SmbFraming.frame(ByteArray(65_536)).copyOf(4)))
    }
    @Test fun `read frame rejects a non zero first byte, a short stream and an oversized length`() {
        assertEquals("0102", hex(SmbFraming.readFrame(byteArrayOf(0, 0, 0, 2, 1, 2).inputStream())!!))
        assertNull(SmbFraming.readFrame(byteArrayOf(1, 0, 0, 2, 1, 2).inputStream()))
        assertNull(SmbFraming.readFrame(byteArrayOf(0, 0, 0, 5, 1).inputStream()))
        assertNull(SmbFraming.readFrame(byteArrayOf(0, 0x01, 0, 1).inputStream()))     // 65537 bytes declared
    }
    @Test fun `the tcp transport sends one frame and reads one reply`() = runBlocking {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { s.accept().use { c ->
            val got = SmbFraming.readFrame(c.getInputStream())!!
            c.getOutputStream().write(SmbFraming.frame(got.reversedArray())); c.getOutputStream().flush()
        } } }
        val t = TcpSmbTransport(Socket(lo, s.localPort), 1000)
        assertEquals("030201", hex(t.exchange(byteArrayOf(1, 2, 3))!!))
        t.close()
    }
    @Test fun `no reply is null`() = runBlocking {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { s.accept().use { Thread.sleep(2_000) } } }
        val t = TcpSmbTransport(Socket(lo, s.localPort), 200)
        assertNull(t.exchange(byteArrayOf(1))); t.close()
    }
    // Review fix round 1: spec 6.3's 3000 ms is per exchange; a per-read timeout let a server dripping a byte per read hold it for hours.
    @Test fun `a server dripping one byte at a time cannot stretch an exchange past its deadline`() = runBlocking {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { s.accept().use { c ->
            SmbFraming.readFrame(c.getInputStream())
            val out = c.getOutputStream(); out.write(byteArrayOf(0, 0, 0, 100)); out.flush()
            repeat(100) { Thread.sleep(DRIP_MS); out.write(1); out.flush() }
        } } }
        val t = TcpSmbTransport(Socket(lo, s.localPort), DEADLINE_MS)
        val started = System.nanoTime()
        assertNull(t.exchange(byteArrayOf(1)))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < DEADLINE_MS + DRIP_MS + SLACK_MS)
        t.close()
    }
    private companion object { const val DRIP_MS = 150L; const val DEADLINE_MS = 300; const val SLACK_MS = 250 }
}
