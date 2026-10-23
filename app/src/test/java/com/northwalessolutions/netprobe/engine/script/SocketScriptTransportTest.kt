package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.common.SocketBinder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/** The real transport against loopback servers started by the test; nothing leaves this machine. */
class SocketScriptTransportTest {
    private val lo = InetAddress.getByName("127.0.0.1")
    private val to = Cleared(lo, "127.0.0.1")
    private val closeables = CopyOnWriteArrayList<Closeable>()
    @After fun tearDown() { closeables.forEach { runCatching { it.close() } } }

    private class Io : IoScope {
        val tracked = CopyOnWriteArrayList<Closeable>()
        @Volatile var dead = false
        override fun track(c: Closeable) { tracked += c; if (dead) { c.close(); throw ScriptAbort(AbortReason.CANCELLED, "stopped") } }
        override fun untrack(c: Closeable) { tracked -= c }
        override fun ensureAlive() { if (dead) throw ScriptAbort(AbortReason.CANCELLED, "stopped") }
    }

    /** Accepts connections forever; [handler] gets each socket and the raw request head read so far. */
    private fun server(handler: (Socket) -> Unit): Int {
        val ss = ServerSocket(0, 20, lo); closeables += ss
        thread(isDaemon = true) { while (true) { val s = try { ss.accept() } catch (_: IOException) { return@thread }; closeables += s; thread(isDaemon = true) { runCatching { handler(s) } } } }
        return ss.localPort
    }

    private fun readHead(s: Socket): String {
        val sb = StringBuilder(); val input = s.getInputStream()
        while (!sb.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) break; sb.append(b.toChar()) }
        return sb.toString()
    }

    private fun get(port: Int, path: String = "/", method: String = "GET", headers: List<Pair<String, String>> = emptyList(), body: ByteArray = ByteArray(0), timeoutMs: Int = 2000, transport: SocketScriptTransport = SocketScriptTransport()) =
        transport.http(to, HttpCall(method, path, false, port, "127.0.0.1:$port", null, headers, body, timeoutMs), Io())

    @Test fun `http reads a content length response and sends the request head`() {
        var head = ""
        val port = server { s -> head = readHead(s); s.getOutputStream().write("HTTP/1.1 200 OK\r\nServer: t\r\nContent-Length: 5\r\n\r\nhello".toByteArray()); s.close() }
        val r = get(port, "/a?b=1", headers = listOf("X-Test" to "1"))
        assertEquals(200, r.status); assertEquals("OK", r.reason); assertEquals("hello", String(r.body)); assertFalse(r.truncated)
        assertEquals("t", r.headers.first { it.first == "Server" }.second)
        assertTrue(head, head.startsWith("GET /a?b=1 HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n"))
        assertTrue(head, head.contains("X-Test: 1\r\n") && head.contains("Connection: close\r\n") && head.contains("User-Agent: NetProbe-tool/1.0\r\n"))
    }

    @Test fun `http posts a body with its length`() {
        var received = ""
        val port = server { s ->
            val input = s.getInputStream(); val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) sb.append(input.read().toChar())
            val len = Regex("Content-Length: (\\d+)").find(sb)!!.groupValues[1].toInt()
            received = sb.toString() + String(ByteArray(len).also { b -> var n = 0; while (n < len) n += input.read(b, n, len - n) })
            s.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n".toByteArray()); s.close()
        }
        val r = get(port, "/p", method = "POST", body = "a=1&b=2".toByteArray())
        assertEquals(201, r.status)
        assertTrue(received, received.startsWith("POST /p HTTP/1.1") && received.contains("Content-Length: 7\r\n") && received.endsWith("a=1&b=2"))
    }

    @Test fun `http reads chunked bodies and bodies that end at close`() {
        val chunked = server { s -> readHead(s); s.getOutputStream().write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2;ext=1\r\nde\r\n0\r\nTrailer: x\r\n\r\n".toByteArray()); s.close() }
        assertEquals("abcde", String(get(chunked).body))
        val close = server { s -> readHead(s); s.getOutputStream().write("HTTP/1.0 200 OK\r\n\r\nuntil close".toByteArray()); s.close() }
        assertEquals("until close", String(get(close).body))
    }

    @Test fun `http skips an interim 100 and does not read a body for HEAD or 204`() {
        val port = server { s -> readHead(s); s.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 204 No Content\r\nX: y\r\n\r\n".toByteArray()); s.close() }
        val r = get(port)
        assertEquals(204, r.status); assertEquals(0, r.body.size)
        val head = server { s -> readHead(s); s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray()); s.close() }
        assertEquals(0, get(head, method = "HEAD").body.size)
    }

    @Test fun `http caps the body at one megabyte and says so`() {
        val port = server { s -> readHead(s); val out = s.getOutputStream(); out.write("HTTP/1.1 200 OK\r\nContent-Length: 2000000\r\n\r\n".toByteArray()); runCatching { val b = ByteArray(65536); repeat(31) { out.write(b) } }; s.close() }
        val r = get(port, timeoutMs = 5000)
        assertEquals(ScriptCaps.MAX_BODY_BYTES, r.body.size); assertTrue(r.truncated)
    }

    @Test fun `http fails on a refused port, a silent server and a non http answer`() {
        val dead = ServerSocket(0, 1, lo).use { it.localPort }
        assertTrue(runCatching { get(dead) }.exceptionOrNull() is IOException)
        val silent = server { s -> readHead(s); Thread.sleep(3000) }
        assertTrue(runCatching { get(silent, timeoutMs = 200) }.exceptionOrNull() is IOException)
        val junk = server { s -> readHead(s); s.getOutputStream().write("SSH-2.0-nope\r\n".toByteArray()); s.close() }
        assertTrue(runCatching { get(junk) }.exceptionOrNull() is IOException)
    }

    @Test fun `closing the tracked sockets ends a blocked read at once`() {
        val port = server { s -> readHead(s); Thread.sleep(10_000) }
        val io = Io()
        val t0 = System.nanoTime()
        val worker = thread { runCatching { SocketScriptTransport().http(to, HttpCall("GET", "/", false, port, "x", null, emptyList(), ByteArray(0), 30_000), io) } }
        while (io.tracked.isEmpty()) Thread.sleep(10)
        Thread.sleep(150)
        io.dead = true; io.tracked.toList().forEach { it.close() }
        worker.join(3000)
        assertFalse(worker.isAlive)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000)
        assertTrue(io.tracked.isEmpty())
    }

    @Test fun `tcp exchange reads a banner, honours readUntil and reports a close`() {
        val port = server { s -> s.getOutputStream().write("SSH-2.0-test\r\nmore".toByteArray()); s.getOutputStream().flush(); Thread.sleep(300); s.close() }
        val t = SocketScriptTransport()
        val until = t.exchange(to, StreamCall(port, ByteArray(0), 2000, "\n".toByteArray(), 4096, false, null), Io())
        assertTrue(String(until.bytes).startsWith("SSH-2.0-test\r\n"))
        val all = t.exchange(to, StreamCall(port, ByteArray(0), 2000, null, 4096, false, null), Io())
        assertEquals("SSH-2.0-test\r\nmore", String(all.bytes)); assertTrue(all.closed)
        val capped = t.exchange(to, StreamCall(port, ByteArray(0), 2000, null, 5, false, null), Io())
        assertEquals("SSH-2", String(capped.bytes)); assertFalse(capped.closed)
    }

    @Test fun `tcp exchange sends bytes and returns what the server answers to them`() {
        val port = server { s -> val b = ByteArray(4); var n = 0; while (n < 4) n += s.getInputStream().read(b, n, 4 - n); s.getOutputStream().write(b.reversedArray()); s.close() }
        val r = SocketScriptTransport().exchange(to, StreamCall(port, byteArrayOf(1, 2, 3, 4), 2000, null, 100, false, null), Io())
        assertEquals(listOf<Byte>(4, 3, 2, 1), r.bytes.toList())
    }

    @Test fun `tcp exchange timeout returns what arrived without failing`() {
        val port = server { s -> s.getOutputStream().write("hi".toByteArray()); Thread.sleep(2000) }
        val r = SocketScriptTransport().exchange(to, StreamCall(port, ByteArray(0), 200, null, 100, false, null), Io())
        assertEquals("hi", String(r.bytes)); assertFalse(r.closed)
    }

    @Test fun `tcp connect keeps a conversation going`() {
        val port = server { s ->
            s.getOutputStream().write("220 ready\r\n".toByteArray())
            val b = ByteArray(6); var n = 0; while (n < 6) n += s.getInputStream().read(b, n, 6 - n)
            s.getOutputStream().write("250 ${String(b).trim()}\r\n".toByteArray()); Thread.sleep(500)
        }
        val io = Io()
        val c = SocketScriptTransport().connect(to, StreamCall(port, ByteArray(0), 2000, null, 100, false, null), io)
        assertEquals("220 ready\r\n", String(c.recv(100, 1000, "\n".toByteArray()).bytes))
        c.send("HELO a".toByteArray())
        assertEquals("250 HELO a\r\n", String(c.recv(100, 1000, "\n".toByteArray()).bytes))
        c.close()
        assertTrue(io.tracked.isEmpty())
    }

    @Test fun `udp exchange sends a datagram and gets the answer or a timeout`() {
        val server = DatagramSocket(0, lo); closeables += server
        thread(isDaemon = true) { val p = DatagramPacket(ByteArray(100), 100); while (true) { try { server.receive(p) } catch (_: IOException) { return@thread }; server.send(DatagramPacket("re:${String(p.data, 0, p.length)}".toByteArray(), 0, 3 + p.length, p.socketAddress)) } }
        val t = SocketScriptTransport()
        val r = t.datagram(to, DatagramCall(server.localPort, "ping".toByteArray(), 1000, 64), Io())
        assertEquals("re:ping", String(r.bytes!!))
        val silent = DatagramSocket(0, lo); closeables += silent
        assertNull(t.datagram(to, DatagramCall(silent.localPort, "x".toByteArray(), 200, 64), Io()).bytes)
    }

    @Test fun `tls certificate reads the chain and facts, and http over tls works`() {
        val ks = KeyStore.getInstance("PKCS12"); File("src/test/resources/tls/rsa2048.p12").inputStream().use { ks.load(it, "changeit".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); kmf.init(ks, "changeit".toCharArray())
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val ss = ctx.serverSocketFactory.createServerSocket(0, 20, lo) as SSLServerSocket; closeables += ss
        thread(isDaemon = true) {
            while (true) {
                val s = try { ss.accept() } catch (_: IOException) { return@thread }
                runCatching { s.use { readHeadIfAny(it) } }
            }
        }
        val t = SocketScriptTransport()
        val chain = t.certificate(to, ss.localPort, "rsa.lan", 3000, Io())
        val leaf = chain.certificates.first()
        assertTrue(leaf.subject, leaf.subject.contains("rsa.lan")); assertEquals("RSA", leaf.keyAlgorithm); assertEquals(2048, leaf.keyBits)
        assertEquals(64, leaf.sha256.length); assertEquals(40, leaf.sha1.length); assertTrue(leaf.notAfter > leaf.notBefore); assertTrue(chain.protocol.startsWith("TLS"))
        val r = t.http(to, HttpCall("GET", "/", true, ss.localPort, "rsa.lan", "rsa.lan", emptyList(), ByteArray(0), 3000), Io())
        assertEquals("tls ok", String(r.body))
    }

    private fun readHeadIfAny(s: Socket) {
        val sb = StringBuilder(); val input = s.getInputStream()
        s.soTimeout = 500
        try { while (!sb.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) return; sb.append(b.toChar()) } } catch (_: IOException) { return }
        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\ntls ok".toByteArray())
    }

    @Test fun `addresses inside the lan use the binder and others do not`() {
        val bound = AtomicInteger(0)
        val binder = object : SocketBinder {
            override fun bind(socket: Socket) { bound.incrementAndGet() }
            override fun bind(socket: DatagramSocket) { bound.incrementAndGet() }
            override fun openConnection(url: URL): URLConnection = throw UnsupportedOperationException()
        }
        val port = server { s -> s.getOutputStream().write("x".toByteArray()); s.close() }
        SocketScriptTransport(binder, lanContains = { true }).exchange(to, StreamCall(port, ByteArray(0), 1000, null, 10, false, null), Io())
        assertEquals(1, bound.get())
        SocketScriptTransport(binder, lanContains = { false }).exchange(to, StreamCall(port, ByteArray(0), 1000, null, 10, false, null), Io())
        assertEquals(1, bound.get())
        SocketScriptTransport(null).exchange(to, StreamCall(port, ByteArray(0), 1000, null, 10, false, null), Io())
        assertEquals(1, bound.get())
    }

    @Test fun `resolve and reverse answer for the loopback address`() {
        val t = SocketScriptTransport()
        assertEquals(listOf("127.0.0.1"), t.resolve(ClearedName("127.0.0.1"), Io()).map { it.hostAddress })
        assertTrue(t.resolve(ClearedName("localhost"), Io()).isNotEmpty())
        val name = t.reverse(to, Io())
        assertTrue(name == null || name.isNotEmpty())
    }
}
