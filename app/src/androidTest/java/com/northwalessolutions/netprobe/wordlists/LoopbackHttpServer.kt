package com.northwalessolutions.netprobe.wordlists

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import kotlin.concurrent.thread

/** A scripted HTTP/1.1 server on 127.0.0.1 for the URL import tests: one request per connection, then close. Never leaves the machine. */
class LoopbackHttpServer(private val handler: (Request) -> Response) : Closeable {
    data class Request(val method: String, val path: String, val headers: Map<String, String>)
    /** [head] is the status line and headers including the blank line; [body] writes the rest and may block (a slow body). */
    class Response(val head: String, val body: (OutputStream) -> Unit = {})

    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
    val port: Int = server.localPort
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    init { thread(isDaemon = true, name = "loopback-http") { acceptLoop() } }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val s = try { server.accept() } catch (_: IOException) { return }   // closed by close(): the loop ends
            thread(isDaemon = true) { serve(s) }
        }
    }
    private fun serve(s: Socket) {
        s.use { socket ->
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val first = input.readLine() ?: return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val parts = first.split(' ')
            val req = Request(parts[0], parts.getOrElse(1) { "/" }, headers)
            requests += req
            val r = handler(req)
            try {
                val out = BufferedOutputStream(socket.getOutputStream())
                out.write(r.head.toByteArray(Charsets.ISO_8859_1)); r.body(out); out.flush()
            } catch (_: IOException) {
                // the client hung up: expected for capped, refused and cancelled reads
            }
        }
    }
    fun url(path: String): URL = URL("http://$LOOPBACK:$port$path")
    override fun close() { server.close() }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 50
        fun ok(type: String?, body: ByteArray, contentLength: Boolean = true) = Response(
            "HTTP/1.1 200 OK\r\n" + (type?.let { "Content-Type: $it\r\n" } ?: "") + (if (contentLength) "Content-Length: ${body.size}\r\n" else "") +
                "Connection: close\r\n\r\n") { it.write(body) }
        fun status(code: Int, reason: String) = Response("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        fun redirect(code: Int, location: String?) = Response("HTTP/1.1 $code Moved\r\n" + (location?.let { "Location: $it\r\n" } ?: "") +
            "Content-Length: 0\r\nConnection: close\r\n\r\n")
    }
}
