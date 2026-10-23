package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.common.HttpClient
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.common.bindOrFail
import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * The real [ScriptTransport]: blocking java.net sockets, called from script and pool threads. Every socket is registered with the run's
 * [IoScope] before it connects, so Stop closes it and the blocked call returns at once. Connections go to the exact address the scope
 * gate approved. [binder] is the Wi-Fi or Ethernet network for addresses inside the LAN ([lanContains]); other addresses use the system route.
 * TLS is trust-all and used only to read, as in HttpClient.
 */
class SocketScriptTransport(
    private val binder: SocketBinder? = null,
    private val lanContains: (InetAddress) -> Boolean = { true },
    sslContext: (() -> SSLContext)? = null,
) : ScriptTransport {
    private val tlsContext: SSLContext by lazy { (sslContext ?: HttpClient.Companion::trustAllContext)() }

    private fun bound(socket: Socket, to: InetAddress) { if (binder != null && lanContains(to)) binder.bindOrFail(socket) }

    /** Connects a tracked socket (and TLS over it when asked); the caller closes the result, and closing it closes the base socket too. */
    private fun open(to: Cleared, port: Int, tls: Boolean, sni: String?, timeoutMs: Int, io: IoScope): Socket {
        val raw = Socket()
        io.track(raw)
        try {
            bound(raw, to.address)
            raw.connect(InetSocketAddress(to.address, port), timeoutMs)
            io.ensureAlive()
            raw.soTimeout = timeoutMs
            raw.tcpNoDelay = true
            if (!tls) return raw
            val name = sni ?: to.address.hostAddress.orEmpty()
            val ssl = tlsContext.socketFactory.createSocket(raw, name, port, true) as SSLSocket
            io.track(ssl)
            runCatching { ssl.enabledProtocols = ssl.supportedProtocols }
            runCatching { ssl.enabledCipherSuites = ssl.supportedCipherSuites }
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            io.ensureAlive()
            return ssl
        } catch (t: Throwable) {
            runCatching { raw.close() }; io.untrack(raw)
            throw t
        }
    }

    private fun release(socket: Socket, io: IoScope) { io.untrack(socket); runCatching { socket.close() } }

    // http

    override fun http(to: Cleared, call: HttpCall, io: IoScope): HttpReply {
        val started = System.nanoTime()
        val socket = open(to, call.port, call.tls, call.sni, call.timeoutMs, io)
        try {
            val head = StringBuilder()
                .append(call.method).append(' ').append(call.pathAndQuery).append(" HTTP/1.1\r\n")
                .append("Host: ").append(call.hostHeader).append("\r\n")
            val names = call.headers.map { it.first.lowercase() }.toSet()
            if ("user-agent" !in names) head.append("User-Agent: ").append(USER_AGENT).append("\r\n")
            if ("accept" !in names) head.append("Accept: */*\r\n")
            for ((n, v) in call.headers) if (!n.equals("Host", true)) head.append(n).append(": ").append(v).append("\r\n")
            if (call.body.isNotEmpty() || call.method == "POST" || call.method == "PUT") head.append("Content-Length: ").append(call.body.size).append("\r\n")
            head.append("Connection: close\r\n\r\n")
            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (call.body.isNotEmpty()) out.write(call.body)
            out.flush()
            val deadline = System.nanoTime() + DEADLINE_FACTOR * call.timeoutMs * NANOS_PER_MS
            val reply = HttpReader(socket.getInputStream(), deadline, io).read(call.method)
            io.ensureAlive()
            return HttpReply(reply.status, reply.reason, reply.headers, reply.body, reply.truncated, elapsedMs(started))
        } finally { release(socket, io) }
    }

    private class RawReply(val status: Int, val reason: String, val headers: List<Pair<String, String>>, val body: ByteArray, val truncated: Boolean)

    /** HTTP/1.x response reader with the head capped at 16 KB and the body at 1 MB, under an overall deadline. */
    private class HttpReader(private val input: InputStream, private val deadlineNs: Long, private val io: IoScope) {
        private var headBytes = 0
        private fun past() = System.nanoTime() > deadlineNs

        private fun line(): String? {
            val sb = StringBuilder()
            while (true) {
                if (past()) throw SocketTimeoutException("deadline")
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (++headBytes > MAX_HEAD_BYTES) throw IOException("response head too large")
                if (b == '\n'.code) return sb.toString()
                if (b != '\r'.code) sb.append(b.toChar())
            }
        }

        fun read(method: String): RawReply {
            var statusLine = line() ?: throw IOException("no response")
            var m = STATUS.matchEntire(statusLine) ?: throw IOException("not an HTTP response")
            while (m.groupValues[1].toInt() in 100..199) {
                while (true) { val h = line() ?: throw IOException("no response"); if (h.isEmpty()) break }
                statusLine = line() ?: throw IOException("no response")
                m = STATUS.matchEntire(statusLine) ?: throw IOException("not an HTTP response")
            }
            val status = m.groupValues[1].toInt(); val reason = m.groupValues[2].trim()
            val headers = ArrayList<Pair<String, String>>()
            while (true) {
                val h = line() ?: break
                if (h.isEmpty()) break
                val i = h.indexOf(':'); if (i > 0) headers += h.substring(0, i).trim() to h.substring(i + 1).trim()
            }
            val length = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.toLongOrNull()?.takeIf { it >= 0 }
            val chunked = headers.any { it.first.equals("Transfer-Encoding", true) && it.second.contains("chunked", true) }
            if (method == "HEAD" || status == 204 || status == 304) return RawReply(status, reason, headers, ByteArray(0), false)
            val out = ByteArrayOutputStream()
            var truncated = false
            try {
                if (chunked) truncated = chunked(out)
                else if (length != null) { truncated = length > ScriptCaps.MAX_BODY_BYTES; fixed(out, minOf(length, ScriptCaps.MAX_BODY_BYTES.toLong())) }
                else truncated = toClose(out)
            } catch (_: SocketTimeoutException) { /* a stalled body keeps what arrived, as HttpClient does */ }
            return RawReply(status, reason, headers, out.toByteArray(), truncated)
        }

        private fun fixed(out: ByteArrayOutputStream, length: Long) {
            val buf = ByteArray(BUFFER)
            var left = length
            while (left > 0 && !past()) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break
                out.write(buf, 0, n); left -= n
                io.ensureAlive()
            }
        }

        private fun toClose(out: ByteArrayOutputStream): Boolean {
            val buf = ByteArray(BUFFER)
            while (!past()) {
                val n = input.read(buf, 0, minOf(buf.size, ScriptCaps.MAX_BODY_BYTES - out.size() + 1)); if (n < 0) return false
                if (out.size() + n > ScriptCaps.MAX_BODY_BYTES) { out.write(buf, 0, ScriptCaps.MAX_BODY_BYTES - out.size()); return true }
                out.write(buf, 0, n)
                io.ensureAlive()
            }
            return false
        }

        private fun chunked(out: ByteArrayOutputStream): Boolean {
            var truncated = false
            while (!past()) {
                headBytes = 0
                val size = (line() ?: break).substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) { while (true) { headBytes = 0; val t = line(); if (t == null || t.isEmpty()) break }; break }
                var left = size
                val buf = ByteArray(BUFFER)
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size, left)); if (n < 0) return truncated
                    val room = ScriptCaps.MAX_BODY_BYTES - out.size()
                    if (n <= room) out.write(buf, 0, n) else { if (room > 0) out.write(buf, 0, room); truncated = true }
                    left -= n
                    io.ensureAlive()
                }
                headBytes = 0; line()
            }
            return truncated
        }
    }

    // tcp

    override fun exchange(to: Cleared, call: StreamCall, io: IoScope): StreamReply {
        val started = System.nanoTime()
        val socket = open(to, call.port, call.tls, call.sni, call.timeoutMs, io)
        try {
            if (call.send.isNotEmpty()) { socket.getOutputStream().write(call.send); socket.getOutputStream().flush() }
            val reply = readStream(socket, call.maxBytes, call.timeoutMs, call.readUntil, started, io)
            return reply
        } finally { release(socket, io) }
    }

    private fun readStream(socket: Socket, maxBytes: Int, timeoutMs: Int, readUntil: ByteArray?, started: Long, io: IoScope): StreamReply {
        socket.soTimeout = timeoutMs
        val input = socket.getInputStream()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(minOf(BUFFER, maxBytes))
        var closed = false
        val deadline = System.nanoTime() + DEADLINE_FACTOR * timeoutMs * NANOS_PER_MS
        try {
            while (out.size() < maxBytes && System.nanoTime() < deadline) {
                val n = input.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
                if (n < 0) { closed = true; break }
                out.write(buf, 0, n)
                io.ensureAlive()
                if (readUntil != null && endsWithOrContains(out.toByteArray(), readUntil)) break
            }
        } catch (_: SocketTimeoutException) { /* what arrived is the answer */ }
        io.ensureAlive()
        return StreamReply(out.toByteArray(), closed, elapsedMs(started))
    }

    private fun endsWithOrContains(data: ByteArray, needle: ByteArray): Boolean {
        if (needle.size > data.size) return false
        outer@ for (i in data.size - needle.size downTo maxOf(0, data.size - needle.size - BUFFER)) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    override fun connect(to: Cleared, call: StreamCall, io: IoScope): StreamConnection {
        val socket = open(to, call.port, call.tls, call.sni, call.timeoutMs, io)
        return object : StreamConnection {
            override fun send(bytes: ByteArray) { io.ensureAlive(); socket.getOutputStream().write(bytes); socket.getOutputStream().flush() }
            override fun recv(maxBytes: Int, timeoutMs: Int, readUntil: ByteArray?): StreamReply = readStream(socket, maxBytes, timeoutMs, readUntil, System.nanoTime(), io)
            override fun close() { release(socket, io) }
        }
    }

    // udp

    override fun datagram(to: Cleared, call: DatagramCall, io: IoScope): DatagramReply {
        val started = System.nanoTime()
        val socket = DatagramSocket(null)
        io.track(socket)
        try {
            binder?.takeIf { lanContains(to.address) }?.bindOrFail(socket)
            socket.broadcast = false
            socket.soTimeout = call.timeoutMs
            socket.connect(InetSocketAddress(to.address, call.port))
            socket.send(DatagramPacket(call.send, call.send.size))
            val buf = ByteArray(call.maxBytes)
            val packet = DatagramPacket(buf, buf.size)
            return try {
                socket.receive(packet)
                io.ensureAlive()
                DatagramReply(buf.copyOf(packet.length), elapsedMs(started))
            } catch (_: SocketTimeoutException) { DatagramReply(null, elapsedMs(started)) }
        } finally { io.untrack(socket); runCatching { socket.close() } }
    }

    // tls

    override fun certificate(to: Cleared, port: Int, sni: String?, timeoutMs: Int, io: IoScope): CertChain {
        val socket = open(to, port, true, sni, timeoutMs, io) as SSLSocket
        try {
            val session = socket.session
            val certs = session.peerCertificates.filterIsInstance<X509Certificate>().map(::facts)
            return CertChain(session.protocol, session.cipherSuite, certs)
        } finally { release(socket, io) }
    }

    private fun facts(c: X509Certificate): CertFacts {
        val sans = runCatching { c.subjectAlternativeNames?.mapNotNull { e -> e.getOrNull(1)?.toString() } }.getOrNull().orEmpty()
        val key = c.publicKey
        val bits = when (key) {
            is RSAPublicKey -> key.modulus.bitLength()
            is ECPublicKey -> key.params.order.bitLength()
            else -> null
        }
        val subject = c.subjectX500Principal.name; val issuer = c.issuerX500Principal.name
        return CertFacts(subject, issuer, sans, c.notBefore.time, c.notAfter.time, key.algorithm, bits, c.sigAlgName, c.serialNumber.toString(16),
            digest("SHA-256", c.encoded), digest("SHA-1", c.encoded), subject == issuer)
    }

    private fun digest(algorithm: String, data: ByteArray): String = Bytes.toHex(MessageDigest.getInstance(algorithm).digest(data))

    // dns

    override fun resolve(name: ClearedName, io: IoScope): List<InetAddress> = lookup(io) { InetAddress.getAllByName(name.name).toList() }

    override fun reverse(to: Cleared, io: IoScope): String? = lookup(io) {
        val literal = to.address.hostAddress
        InetAddress.getByAddress(to.address.address).canonicalHostName.takeIf { it != literal }
    }

    /** DNS cannot be interrupted, so it runs on a pool thread and the caller waits in slices that notice a Stop; a slow server costs the pool thread only. */
    private fun <T> lookup(io: IoScope, block: () -> T): T {
        val future = DNS_POOL.submit(Callable { block() })
        val deadline = System.nanoTime() + DNS_TIMEOUT_MS * NANOS_PER_MS
        while (true) {
            io.ensureAlive()
            try { return future.get(WAIT_SLICE_MS, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { if (System.nanoTime() > deadline) { future.cancel(true); throw IOException(ScriptCopy.DNS_TIMEOUT) } }
            catch (e: ExecutionException) { throw (e.cause as? IOException) ?: IOException(ScriptCopy.DNS_FAILED) }
        }
    }

    private fun elapsedMs(startNs: Long): Int = ((System.nanoTime() - startNs) / NANOS_PER_MS).toInt()

    companion object {
        const val USER_AGENT = "NetProbe-tool/1.0"
        private const val MAX_HEAD_BYTES = 16_384
        private const val BUFFER = 8192
        private const val DEADLINE_FACTOR = 2L
        private const val NANOS_PER_MS = 1_000_000L
        private const val DNS_TIMEOUT_MS = 5_000L
        private const val WAIT_SLICE_MS = 50L
        private val STATUS = Regex("HTTP/1\\.[01] (\\d{3})( .*)?")
        private val dnsCount = AtomicInteger(0)
        private val DNS_POOL = Executors.newCachedThreadPool(ThreadFactory { r -> Thread(r, "netprobe-script-dns-${dnsCount.incrementAndGet()}").apply { isDaemon = true } })
    }
}
