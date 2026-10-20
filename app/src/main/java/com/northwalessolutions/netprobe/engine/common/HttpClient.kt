package com.northwalessolutions.netprobe.engine.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class HttpResponse(
    val status: Int, val reason: String,
    val headers: List<Pair<String, String>>,   // in order, names as sent
    val body: ByteArray, val bodyTruncated: Boolean, val contentLength: Long?,
) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    fun headersAll(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

sealed interface HttpFailure { object Refused : HttpFailure; object Timeout : HttpFailure; object Reset : HttpFailure; object Malformed : HttpFailure }
sealed interface HttpResult { data class Ok(val response: HttpResponse) : HttpResult; data class Failed(val failure: HttpFailure) : HttpResult }

/**
 * HTTP/1.1 over a raw socket (spec 4.6): Connection: close, redirects never followed, a 64 KB body cap. Every exchange runs inside
 * CancellableIo.closing, so Stop ends within about a second during a read. TLS is layered over the connected socket with trust-all,
 * used only to read; the browser launch never uses this. The socket comes from Probe.connect, so the Wi-Fi binding (currentBinder)
 * holds off-subnet, and no dispatcher is named here: the caller's dispatcher is inherited.
 */
class HttpClient(private val timeoutMs: Int, private val maxBody: Int = MAX_BODY_BYTES) {
    suspend fun request(ip: Inet4Address, port: Int, tls: Boolean, method: String, path: String, hostName: String? = null, sslContext: SSLContext? = null): HttpResult {
        // Header-injection guard: Location paths come from devices and names from mDNS or reverse DNS (fix round 1).
        if (!isToken(method) || !isToken(path) || (hostName != null && !isToken(hostName))) return HttpResult.Failed(HttpFailure.Malformed)
        val outcome = Probe.connect(ip, port, timeoutMs, keepOpen = true)
        val raw = (outcome as? Probe.Outcome.Connected)?.socket ?: return HttpResult.Failed(failureOf(outcome))
        val tlsSocket = AtomicReference<SSLSocket?>(null)
        val result = try {
            // One hard bound over handshake and read: a drip-fed handshake beats every per-read soTimeout. The timeout cancels the
            // closing scope, whose watcher closes the socket, so the blocked call ends at once.
            withTimeoutOrNull(DEADLINE_FACTOR * timeoutMs + timeoutMs) {
                CancellableIo.closing(raw) { exchange(raw, ip, port, tls, method, path, hostName, sslContext, tlsSocket) }
            } ?: HttpResult.Failed(HttpFailure.Timeout)
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { HttpResult.Failed(HttpFailure.Reset)
        } finally {
            // The SSLSocket first, so the provider frees its native state now rather than in a finalizer; autoClose closes raw too.
            tlsSocket.get()?.let { s -> runCatching { s.close() } }
            runCatching { raw.close() }
        }
        // A cancel that closed the socket mid-read surfaces as a SocketException; the caller sees the cancellation, not a Reset.
        currentCoroutineContext().ensureActive()
        return result
    }

    private fun failureOf(o: Probe.Outcome): HttpFailure = when (o) {
        is Probe.Outcome.Refused -> HttpFailure.Refused
        is Probe.Outcome.Timeout, is Probe.Outcome.Unreachable -> HttpFailure.Timeout
        else -> HttpFailure.Reset
    }

    private fun exchange(raw: Socket, ip: Inet4Address, port: Int, tls: Boolean, method: String, path: String, hostName: String?, sslContext: SSLContext?, tlsSocket: AtomicReference<SSLSocket?>): HttpResult {
        // Before the handshake (plan review G4): a device that accepts TCP and never answers the ClientHello times out instead of
        // hanging the run. The connect itself is bounded by Probe.connect's timeout argument.
        raw.soTimeout = timeoutMs
        val socket: Socket = if (!tls) raw else try { layerTls(raw, ip, port, hostName, sslContext, tlsSocket) }
            catch (e: IOException) { return HttpResult.Failed(if (isTimeout(e)) HttpFailure.Timeout else HttpFailure.Reset) }
        socket.soTimeout = timeoutMs
        val deadline = System.currentTimeMillis() + DEADLINE_FACTOR * timeoutMs       // overall, so a dripping server still stops
        val host = hostHeader(ip, port, tls, hostName)
        val head = "$method $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: $USER_AGENT\r\nAccept: */*\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); flush() }
        return try { read(BufferedInputStream(socket.getInputStream(), READ_BUFFER), method, deadline) } catch (_: SocketTimeoutException) { HttpResult.Failed(HttpFailure.Timeout) } catch (_: SocketException) { HttpResult.Failed(HttpFailure.Reset) }
    }

    /** A handshake read timeout can arrive bare or wrapped in an SSLException, depending on the TLS provider. */
    private fun isTimeout(e: Throwable): Boolean {
        var t: Throwable? = e
        repeat(MAX_CAUSE_DEPTH) { if (t is SocketTimeoutException) return true; t = t?.cause ?: return false }
        return false
    }

    private fun layerTls(raw: Socket, ip: Inet4Address, port: Int, hostName: String?, sslContext: SSLContext?, tlsSocket: AtomicReference<SSLSocket?>): SSLSocket {
        val ctx = sslContext ?: trustAllContext()
        val sni = hostName ?: ip.hostAddress
        val s = ctx.socketFactory.createSocket(raw, sni, port, true) as SSLSocket
        tlsSocket.set(s)   // before the handshake, so a failed or timed-out handshake still closes it explicitly
        // Enable every protocol and cipher so old TLS 1.0/1.1 and RSA/3DES-only devices can still be read; each setter is wrapped (spec 4.5).
        runCatching { s.enabledProtocols = s.supportedProtocols }
        runCatching { s.enabledCipherSuites = s.supportedCipherSuites }
        s.startHandshake()
        return s
    }

    private fun read(input: InputStream, method: String, deadline: Long): HttpResult {
        val reader = HeadReader(input, deadline)
        // A head line that ends without a line means the deadline passed (a slow drip, so Timeout) or the stream ended or overflowed.
        val cut = { HttpResult.Failed(if (reader.deadlineHit) HttpFailure.Timeout else HttpFailure.Malformed) }
        var statusLine = reader.line() ?: return cut()
        var m = STATUS.matchEntire(statusLine) ?: return HttpResult.Failed(HttpFailure.Malformed)
        while (m.groupValues[1].toInt() in 100..199) {                   // skip an interim 1xx head, headers and all, then read the next status
            while (true) { val h = reader.line() ?: return cut(); if (h.isEmpty()) break }
            statusLine = reader.line() ?: return cut()
            m = STATUS.matchEntire(statusLine) ?: return HttpResult.Failed(HttpFailure.Malformed)
        }
        val status = m.groupValues[1].toInt(); val reason = m.groupValues[2].trim()
        val headers = ArrayList<Pair<String, String>>()
        while (true) {
            val h = reader.line() ?: return cut()
            if (h.isEmpty()) break
            if (reader.headBytes > MAX_HEADER_BYTES) return HttpResult.Failed(HttpFailure.Malformed)
            val i = h.indexOf(':'); if (i > 0) headers += h.substring(0, i).trim() to h.substring(i + 1).trim()
        }
        if (reader.headBytes > MAX_HEADER_BYTES) return HttpResult.Failed(HttpFailure.Malformed)
        val contentLength = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.toLongOrNull()?.takeIf { it >= 0 }
        val chunked = headers.any { it.first.equals("Transfer-Encoding", true) && it.second.contains("chunked", true) }
        val bodyless = method == "HEAD" || status in 100..199 || status == 204 || status == 304
        if (bodyless) return HttpResult.Ok(HttpResponse(status, reason, headers, ByteArray(0), false, contentLength))
        val (body, truncated) = when {
            chunked -> readChunked(reader, deadline)
            contentLength != null -> readFixed(reader, contentLength, deadline)
            else -> readToClose(reader, deadline)
        }
        return HttpResult.Ok(HttpResponse(status, reason, headers, body, truncated, contentLength))
    }

    // Every body reader treats a read timeout as the end of the body: the status, headers and bytes so far are kept (fix round 1).
    private fun readFixed(r: HeadReader, length: Long, deadline: Long): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream(); var remaining = minOf(length, maxBody.toLong()).toInt()
        try {
            while (remaining > 0 && System.currentTimeMillis() <= deadline) { val b = r.readByte() ?: break; out.write(b); remaining-- }
        } catch (_: SocketTimeoutException) { return out.toByteArray() to false }
        return out.toByteArray() to (length > maxBody)
    }
    /** No framing: read until close, the cap or the overall deadline; a read timeout ends the body, it does not fail the exchange. */
    private fun readToClose(r: HeadReader, deadline: Long): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream()
        try {
            while (out.size() < maxBody && System.currentTimeMillis() <= deadline) { val b = r.readByte() ?: return out.toByteArray() to false; out.write(b) }
            return out.toByteArray() to (out.size() >= maxBody && r.readByte() != null)
        } catch (_: SocketTimeoutException) { return out.toByteArray() to false }
    }
    private fun readChunked(r: HeadReader, deadline: Long): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream(); var truncated = false
        try {
            while (System.currentTimeMillis() <= deadline) {
                r.resetHeadBytes()   // each chunk-size line gets its own 16 KB bound; many small chunks must not add up to the head cap
                val sizeLine = r.line() ?: break
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) { while (true) { r.resetHeadBytes(); val t = r.line(); if (t == null || t.isEmpty()) break }; break }   // trailers then end
                var left = size
                while (left > 0) { val b = r.readByte() ?: return out.toByteArray() to truncated; if (out.size() < maxBody) out.write(b) else truncated = true; left-- }
                r.resetHeadBytes(); r.line()   // the CRLF after the chunk data
            }
        } catch (_: SocketTimeoutException) { return out.toByteArray() to truncated }
        return out.toByteArray() to truncated
    }

    /** Reads a byte-oriented stream a line at a time (CRLF or bare LF) and single bytes, counting head bytes for the 16 KB cap. */
    private class HeadReader(private val input: InputStream, private val deadline: Long) {
        var headBytes = 0; private set
        /** True once a read stopped because the overall deadline passed, so a cut head reads as Timeout, not Malformed. */
        var deadlineHit = false; private set
        fun resetHeadBytes() { headBytes = 0 }
        private fun pastDeadline(): Boolean {
            if (System.currentTimeMillis() > deadline) deadlineHit = true
            return deadlineHit
        }
        fun readByte(): Int? {
            if (pastDeadline()) return null
            val b = input.read(); return if (b < 0) null else b
        }
        /** One line, or null at EOF before any byte, past the deadline (a partial line is never returned), or past the header cap. */
        fun line(): String? {
            val sb = StringBuilder()
            while (true) {
                if (pastDeadline()) return null
                val b = input.read(); if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                headBytes++
                if (headBytes > MAX_HEADER_BYTES) return null       // a line with no LF never grows past the header cap (plan review G15)
                if (b == '\n'.code) return sb.toString()
                if (b != '\r'.code) sb.append(b.toChar())
            }
        }
    }

    companion object {
        const val MAX_BODY_BYTES = 65_536; const val MAX_HEADER_BYTES = 16_384; const val USER_AGENT = "NetProbe/0.1"
        private const val READ_BUFFER = 8192
        /** The whole exchange after connect may take this many read timeouts, so a server dripping one byte at a time still ends. */
        private const val DEADLINE_FACTOR = 2L
        private const val MAX_CAUSE_DEPTH = 8
        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443
        private const val FIRST_VISIBLE_ASCII = 0x21
        private const val LAST_VISIBLE_ASCII = 0x7E
        /** True when every char is visible ASCII: no space, control, DEL or non-ASCII, so nothing can split the request head. */
        private fun isToken(s: String): Boolean = s.isNotEmpty() && s.all { it.code in FIRST_VISIBLE_ASCII..LAST_VISIBLE_ASCII }
        /** The Host header (spec 4.6): the name when given, else the IP, plus ":<port>" unless 80 for http or 443 for https. */
        fun hostHeader(ip: Inet4Address, port: Int, tls: Boolean, hostName: String?): String {
            val name = hostName ?: ip.hostAddress
            val defaultPort = if (tls) HTTPS_PORT else HTTP_PORT
            return if (port == defaultPort) name else "$name:$port"
        }
        private val STATUS = Regex("HTTP/1\\.[01] (\\d{3})( .*)?")
        /** Trust-all, used only to read (spec 4.5); the browser launch never uses this. One per run resumes the session cache. */
        fun trustAllContext(): SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }), null)
        }
    }
}
