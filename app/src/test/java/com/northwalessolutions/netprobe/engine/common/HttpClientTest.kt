package com.northwalessolutions.netprobe.engine.common

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** spec 4.6 and 12.1: exact request bytes, framing, caps, failures and cancellation, against loopback ServerSocket scripts. */
class HttpClientTest {
    private val lo = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val ip9 = InetAddress.getByName("10.0.3.9") as Inet4Address
    private val servers = mutableListOf<ServerSocket>()
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }

    /** Accepts one connection, hands the request text to [handle], and writes what it returns, then closes (unless [keepOpen]). */
    private fun serve(keepOpen: Boolean = false, handle: (String) -> ByteArray): Pair<Int, () -> String> {
        val s = ServerSocket(0, 50, lo); servers += s
        val seen = java.util.concurrent.atomic.AtomicReference("")   // cross-thread visibility; @Volatile is not allowed on a local
        thread(isDaemon = true) {
            runCatching {
                val c = s.accept()
                val req = StringBuilder(); val input = c.getInputStream()
                // Read the request head (up to the blank line).
                val buf = ByteArray(4096)
                while (true) { val n = input.read(buf); if (n < 0) break; req.append(String(buf, 0, n, Charsets.ISO_8859_1)); if (req.contains("\r\n\r\n")) break }
                seen.set(req.toString())
                c.getOutputStream().write(handle(seen.get())); c.getOutputStream().flush()
                if (!keepOpen) c.close() else { Thread.sleep(400); c.close() }
            }
        }
        return s.localPort to { seen.get() }
    }
    private fun ok(body: String) = ("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body").toByteArray(Charsets.ISO_8859_1)
    private fun client(timeout: Int = 1000) = HttpClient(timeoutMs = timeout)
    private fun result(port: Int, method: String = "GET", path: String = "/", host: String? = null) = runBlocking {
        client().request(lo, port, tls = false, method = method, path = path, hostName = host)
    }

    @Test fun `get request bytes are exact with the ip host and its port`() {
        val (port, seen) = serve { ok("hi") }
        result(port, path = "/x")
        assertEquals("GET /x HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUser-Agent: NetProbe/0.1\r\nAccept: */*\r\nConnection: close\r\n\r\n", seen())
    }
    @Test fun `head sends head and gets no body`() {
        val (port, _) = serve { "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n".toByteArray() }
        val r = result(port, method = "HEAD") as HttpResult.Ok
        assertEquals(200, r.response.status); assertEquals(0, r.response.body.size)
    }
    @Test fun `host uses the name when given and omits the port for 443 on https is covered in the tls task`() {
        val (port, seen) = serve { ok("hi") }
        result(port, host = "chnas.local")
        assertTrue(seen().contains("Host: chnas.local:$port\r\n"))
    }
    @Test fun `content length body is read`() {
        val (port, _) = serve { ok("hello") }
        val r = result(port) as HttpResult.Ok
        assertEquals("hello", String(r.response.body, Charsets.ISO_8859_1)); assertEquals(5L, r.response.contentLength); assertFalse(r.response.bodyTruncated)
    }
    @Test fun `chunked body is de-chunked including an extension and a trailer`() {
        val (port, _) = serve { ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4;ext=1\r\nWiki\r\n5\r\npedia\r\n0\r\nX-T: 1\r\n\r\n").toByteArray() }
        val r = result(port) as HttpResult.Ok
        assertEquals("Wikipedia", String(r.response.body, Charsets.ISO_8859_1)); assertNull(r.response.contentLength)
    }
    @Test fun `a close delimited body ends at eof`() {
        val (port, _) = serve { "HTTP/1.1 200 OK\r\n\r\nno length here".toByteArray() }
        val r = result(port) as HttpResult.Ok
        assertEquals("no length here", String(r.response.body, Charsets.ISO_8859_1))
    }
    @Test fun `no body for 204 and 304`() {
        for (code in listOf(204 to "No Content", 304 to "Not Modified")) {
            val (port, _) = serve { "HTTP/1.1 ${code.first} ${code.second}\r\n\r\n".toByteArray() }
            val r = result(port) as HttpResult.Ok; assertEquals(code.first, r.response.status); assertEquals(0, r.response.body.size)
        }
    }
    @Test fun `interim 100 continue is skipped`() {
        val (port, _) = serve { "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray() }
        val r = result(port) as HttpResult.Ok; assertEquals(200, r.response.status); assertEquals("ok", String(r.response.body, Charsets.ISO_8859_1))
    }
    @Test fun `a bare lf status line and headers are accepted`() {
        val (port, _) = serve { "HTTP/1.1 200 OK\nServer: lwip\nContent-Length: 1\n\nx".toByteArray() }
        val r = result(port) as HttpResult.Ok; assertEquals("lwip", r.response.header("server")); assertEquals("x", String(r.response.body, Charsets.ISO_8859_1))
    }
    @Test fun `the body is capped at sixty four kilobytes and marked truncated`() {
        val big = "a".repeat(100_000)
        val (port, _) = serve(keepOpen = true) { "HTTP/1.1 200 OK\r\n\r\n$big".toByteArray() }
        val r = result(port) as HttpResult.Ok
        assertEquals(HttpClient.MAX_BODY_BYTES, r.response.body.size); assertTrue(r.response.bodyTruncated)
    }
    @Test fun `headers beyond sixteen kilobytes are malformed`() {
        val (port, _) = serve { ("HTTP/1.1 200 OK\r\nX: " + "y".repeat(20_000) + "\r\n\r\n").toByteArray() }
        assertEquals(HttpFailure.Malformed, (result(port) as HttpResult.Failed).failure)
    }
    @Test fun `a non http status line is malformed`() {
        val (port, _) = serve { "220 ftp ready\r\n".toByteArray() }
        assertEquals(HttpFailure.Malformed, (result(port) as HttpResult.Failed).failure)
    }
    @Test fun `a server that never answers times out`() {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { val c = s.accept(); Thread.sleep(5_000); c.close() } }
        assertEquals(HttpFailure.Timeout, (runBlocking { client(300).request(lo, s.localPort, false, "GET", "/") } as HttpResult.Failed).failure)
    }
    @Test fun `a refused port is Refused`() {
        val s = ServerSocket(0, 50, lo); val port = s.localPort; s.close()
        val f = runBlocking { client(300).request(lo, port, false, "GET", "/") }
        assertEquals(HttpFailure.Refused, (f as HttpResult.Failed).failure)
    }
    @Test fun `a redirect is returned and not followed`() {
        val (port, _) = serve { "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/elsewhere\r\nContent-Length: 0\r\n\r\n".toByteArray() }
        val r = result(port) as HttpResult.Ok; assertEquals(302, r.response.status); assertEquals("http://127.0.0.1:1/elsewhere", r.response.header("location"))
    }
    @Test fun `the host header omits only the default port of the scheme`() {
        assertEquals("10.0.3.9", HttpClient.hostHeader(ip9, 80, false, null))
        assertEquals("nas.lan", HttpClient.hostHeader(ip9, 443, true, "nas.lan"))
        assertEquals("10.0.3.9:443", HttpClient.hostHeader(ip9, 443, false, null))
        assertEquals("nas.lan:80", HttpClient.hostHeader(ip9, 80, true, "nas.lan"))
    }
    @Test fun `a server that accepts tcp but stalls tls times out`() {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { val c = s.accept(); Thread.sleep(5_000); c.close() } }
        val t0 = System.nanoTime()
        val r = runBlocking { client(300).request(lo, s.localPort, tls = true, method = "GET", path = "/") }
        assertEquals(HttpFailure.Timeout, (r as HttpResult.Failed).failure)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_500)
    }
    @Test fun `a server that drips bytes is stopped by the overall deadline`() {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { s.accept().use { c ->
            c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray())
            repeat(100) { c.getOutputStream().write('a'.code); c.getOutputStream().flush(); Thread.sleep(50) }
        } } }
        val t0 = System.nanoTime()
        val r = runBlocking { client(300).request(lo, s.localPort, false, "GET", "/") } as HttpResult.Ok
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_500); assertTrue(r.response.body.size < 100)
    }
    @Test fun `no framing on a connection kept open ends at the read timeout with the body so far`() {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { s.accept().use { c ->
            c.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\npartial".toByteArray()); c.getOutputStream().flush(); Thread.sleep(3_000)
        } } }
        val t0 = System.nanoTime()
        val r = runBlocking { client(300).request(lo, s.localPort, false, "GET", "/") } as HttpResult.Ok
        assertEquals("partial", String(r.response.body)); assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_500)
    }
    @Test fun `a megabyte header line with no lf is malformed, not memory`() {
        val (port, _) = serve { ("HTTP/1.1 200 OK\r\nX: " + "a".repeat(1_000_000)).toByteArray() }
        assertEquals(HttpFailure.Malformed, (result(port) as HttpResult.Failed).failure)
    }
    @Test fun `cancellation mid read closes the socket within a second`() = runBlocking {
        val s = ServerSocket(0, 50, lo); servers += s
        val accepted = java.util.concurrent.CountDownLatch(1)
        lateinit var peer: Socket
        thread(isDaemon = true) { runCatching { peer = s.accept(); accepted.countDown(); peer.getOutputStream().write("HTTP/1.1 200 OK\r\n".toByteArray()); peer.getOutputStream().flush(); Thread.sleep(10_000) } }
        val job = launch(Dispatchers.IO) { client(10_000).request(lo, s.localPort, false, "GET", "/") }
        assertTrue(accepted.await(2, java.util.concurrent.TimeUnit.SECONDS)); delay(300)
        val t0 = System.nanoTime(); job.cancelAndJoin()
        assertTrue("cancel took ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) / 1_000_000 <= 1000)
    }
}
