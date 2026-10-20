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
}
