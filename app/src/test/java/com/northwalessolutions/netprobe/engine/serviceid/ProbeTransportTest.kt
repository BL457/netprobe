package com.northwalessolutions.netprobe.engine.serviceid

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** SocketTransport against loopback servers (no outside network): the caps that make a hostile peer harmless. */
class ProbeTransportTest {
    private val lo = InetAddress.getByName("127.0.0.1")
    private val servers = mutableListOf<ServerSocket>()
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }

    private fun serve(body: (Socket) -> Unit): Int {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { while (true) { val c = s.accept(); thread(isDaemon = true) { runCatching { body(c) } } } } }
        return s.localPort
    }

    @Test fun `a hostile reply is cut at maxBytes`() = runBlocking {
        val port = serve { c -> val junk = ByteArray(4096) { 'A'.code.toByte() }; repeat(256) { c.getOutputStream().write(junk) } }   // 1 MB
        val t = SocketTransport()
        assertEquals(ConnectOutcome.CONNECTED, t.connect(lo, port, 1000))
        val got = t.read(2048, 1000)
        t.close()
        assertEquals(2048, got!!.size)
    }
    @Test fun `a slow reply ends at waitMs`() = runBlocking {
        val port = serve { c -> Thread.sleep(2000); c.getOutputStream().write("late".toByteArray()) }
        val t = SocketTransport()
        t.connect(lo, port, 1000)
        val t0 = System.nanoTime()
        val got = t.read(2048, 300)
        val ms = (System.nanoTime() - t0) / 1_000_000
        t.close()
        assertNull(got); assertTrue("took $ms ms", ms in 250..900)
    }
    @Test fun `a peer that dribbles forever still ends at waitMs`() = runBlocking {
        val port = serve { c -> repeat(100) { c.getOutputStream().write('x'.code); c.getOutputStream().flush(); Thread.sleep(100) } }
        val t = SocketTransport(); t.connect(lo, port, 1000)
        val t0 = System.nanoTime(); t.read(2048, 400); val ms = (System.nanoTime() - t0) / 1_000_000; t.close()
        assertTrue("took $ms ms", ms < 1200)
    }
    @Test fun `a refused connect is reported as refused`() = runBlocking {
        val closed = ServerSocket(0, 1, lo).also { it.close() }.localPort
        assertEquals(ConnectOutcome.REFUSED, SocketTransport().connect(lo, closed, 1000))
    }
    @Test fun `a peer that closes is flagged and reads nothing`() = runBlocking {
        val port = serve { c -> c.close() }
        val t = SocketTransport(); t.connect(lo, port, 1000)
        assertNull(t.read(2048, 1000)); assertTrue(t.peerClosed); t.close()
    }
    @Test fun `a line end stops a newline read early`() = runBlocking {
        val port = serve { c -> c.getOutputStream().write("SSH-2.0-x\r\nrest".toByteArray()); Thread.sleep(500) }
        val t = SocketTransport(); t.connect(lo, port, 1000)
        val got = t.read(1024, 1000, stopAtNewline = true); t.close()
        assertTrue(String(got!!).startsWith("SSH-2.0-x"))
    }
}
