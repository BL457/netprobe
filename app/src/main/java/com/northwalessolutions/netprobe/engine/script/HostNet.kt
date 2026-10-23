package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The network half of ctx (spec section 4). Every function: count it against the budget, wait on the run's gate, ask the [ScopeGate] for
 * the one address to use, call the transport with that address, count the bytes. Redirects are followed here, one hop at a time, so each
 * hop meets the gate again (G2). Arguments and results are plain Kotlin data; HostApi turns them into script values.
 */
internal class HostNet(private val s: RunSession, private val transport: ScriptTransport, private val gate: ScopeGate) {
    private class HttpSpec(val method: String, val url: ScriptUrl, val headers: List<Pair<String, String>>, val body: ByteArray, val timeoutMs: Int, val follow: Boolean)

    // http

    private fun parseHttp(o: Opts): HttpSpec {
        val method = o.string("method", "GET").uppercase()
        if (method.isEmpty() || method.length > MAX_METHOD || !method.all { it in 'A'..'Z' }) throw ScriptHostError(ScriptCopy.BAD_METHOD)
        val url = ScriptUrl.parse(o.string("url"))
        val headers = ArrayList<Pair<String, String>>()
        o.map("headers")?.let { h ->
            if (h.size > ScriptCaps.MAX_HEADER_COUNT) throw ScriptHostError(ScriptCopy.TOO_MANY_HEADERS)
            for ((name, value) in h) {
                val v = value as? String ?: (value as? Double)?.let { n -> if (n == Math.floor(n)) n.toLong().toString() else n.toString() } ?: throw ScriptHostError(ScriptCopy.badHeader(name))
                if (!isToken(name) || name.lowercase() in FORBIDDEN_HEADERS || v.length > ScriptCaps.MAX_HEADER_VALUE_CHARS || v.any { it.code < SPACE && it != '\t' || it.code == DEL })
                    throw ScriptHostError(ScriptCopy.badHeader(name.take(ECHO)))
                headers += name to v
            }
        }
        val body = o.bytes("body") ?: ByteArray(0)
        return HttpSpec(method, url, headers, body, o.int("timeoutMs", ScriptCaps.DEFAULT_HTTP_TIMEOUT_MS, ScriptCaps.MIN_TIMEOUT_MS, ScriptCaps.MAX_TIMEOUT_MS), o.bool("followRedirects", false))
    }

    fun request(o: Opts): Map<String, Any?> {
        val spec = parseHttp(o)
        s.chargeCalls(1)
        s.gateWait()
        return perform(spec)
    }

    private fun perform(spec: HttpSpec): Map<String, Any?> {
        var url = spec.url
        var method = spec.method
        var body = spec.body
        var hops = 0
        while (true) {
            s.ensureAlive()
            val to = gate.clearConnect(url.host, url.port)
            val hostHeader = spec.headers.firstOrNull { it.first.equals("Host", true) }?.second ?: url.hostHeader
            val call = HttpCall(method, url.pathAndQuery, url.tls, url.port, hostHeader, sniFor(url.host), spec.headers, body, spec.timeoutMs)
            val reply = s.hostCall { transport.http(to, call, s) }
            s.addTransfer(reply.body.size.toLong() + body.size + reply.headers.sumOf { (it.first.length + it.second.length).toLong() })
            val location = reply.headers.firstOrNull { it.first.equals("Location", true) }?.second
            if (spec.follow && reply.status in REDIRECTS && location != null) {
                if (++hops > ScriptCaps.MAX_REDIRECTS) throw ScriptHostError(ScriptCopy.TOO_MANY_REDIRECTS)
                url = url.resolve(location)
                if (reply.status == 303 || ((reply.status == 301 || reply.status == 302) && method == "POST")) { method = "GET"; body = ByteArray(0) }
                s.chargeCalls(1)
                s.gateWait()
                continue
            }
            return describe(reply, url)
        }
    }

    private fun sniFor(host: String): String? = if (host.contains(':') || host.all { it.isDigit() || it == '.' }) null else host

    private fun describe(r: HttpReply, url: ScriptUrl): Map<String, Any?> {
        val headers = LinkedHashMap<String, String>()
        for ((n, v) in r.headers) headers[n] = headers[n]?.let { "$it, $v" } ?: v
        val text = Bytes.toUtf8(r.body)
        return mapOf(
            "status" to r.status.toDouble(), "statusText" to r.reason, "headers" to headers,
            "headerList" to r.headers.map { mapOf("name" to it.first, "value" to it.second) },
            "body" to text, "bodyBytes" to r.body.copyOf(minOf(r.body.size, ScriptCaps.MAX_ARRAY_LENGTH)),
            "bodyBytesTruncated" to (r.truncated || r.body.size > ScriptCaps.MAX_ARRAY_LENGTH),
            "url" to url.text, "timeMs" to r.timeMs.toDouble(),
        )
    }

    fun many(requests: List<Opts>, concurrencyArg: Int): List<Map<String, Any?>> {
        val specs = requests.map(::parseHttp)
        if (specs.isEmpty()) return emptyList()
        s.chargeCalls(specs.size)
        val workers = concurrencyArg.coerceIn(1, ScriptCaps.MANY_MAX_CONCURRENCY).coerceAtMost(specs.size)
        val pool = Executors.newFixedThreadPool(workers, POOL_THREADS)
        val violation = AtomicReference<ScopeViolation?>(null)
        try {
            val futures: List<Future<Map<String, Any?>>> = specs.map { spec ->
                pool.submit(Callable {
                    try {
                        s.gateWait()
                        perform(spec)
                    } catch (e: ScopeViolation) {
                        violation.compareAndSet(null, e); failedItem(e.message ?: ScriptCopy.HOST_FAILED)
                    } catch (e: ScriptAbort) {
                        throw e
                    } catch (e: ScriptHostError) {
                        failedItem(e.message ?: ScriptCopy.HOST_FAILED)
                    } catch (e: IOException) {
                        failedItem(ScriptCopy.ioFailed(e.javaClass.simpleName, e.message))
                    }
                })
            }
            val out = ArrayList<Map<String, Any?>>(futures.size)
            for (f in futures) out += await(f)
            violation.get()?.let { throw it }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    private fun failedItem(message: String): Map<String, Any?> = mapOf("status" to 0.0, "error" to message)

    private fun await(f: Future<Map<String, Any?>>): Map<String, Any?> {
        while (true) {
            s.ensureAlive()
            try { return f.get(AWAIT_SLICE_MS, TimeUnit.MILLISECONDS) }
            catch (_: java.util.concurrent.TimeoutException) { continue }
            catch (e: ExecutionException) { throw e.cause ?: e }
            catch (_: InterruptedException) { s.abort(AbortReason.CANCELLED); s.ensureAlive() }
        }
    }

    // tcp, udp, tls

    private fun streamCall(o: Opts, withSend: Boolean): StreamCall = StreamCall(
        port = o.requiredInt("port", 1, PORT_MAX), send = if (withSend) o.bytes("send") ?: ByteArray(0) else ByteArray(0),
        timeoutMs = o.int("timeoutMs", ScriptCaps.DEFAULT_HTTP_TIMEOUT_MS, ScriptCaps.MIN_TIMEOUT_MS, ScriptCaps.MAX_TIMEOUT_MS),
        readUntil = o.bytes("readUntil")?.takeIf { it.isNotEmpty() }, maxBytes = o.int("maxBytes", ScriptCaps.DEFAULT_TCP_MAX_BYTES, 1, ScriptCaps.MAX_ARRAY_LENGTH),
        tls = o.bool("tls", false), sni = o.optString("sni"),
    )

    private fun streamResult(r: StreamReply): Map<String, Any?> =
        mapOf("bytes" to r.bytes, "text" to Bytes.toUtf8(r.bytes), "closed" to r.closed, "timeMs" to r.timeMs.toDouble())

    fun tcpExchange(o: Opts): Map<String, Any?> {
        val call = streamCall(o, true)
        val host = o.string("host")
        s.chargeCalls(1); s.gateWait()
        val to = gate.clearConnect(host, call.port)
        val reply = s.hostCall { transport.exchange(to, call, s) }
        s.addTransfer(reply.bytes.size.toLong() + call.send.size)
        return streamResult(reply)
    }

    /** A script-side handle on an open connection; closing is idempotent and the run's end closes whatever is left. */
    inner class Handle(private val conn: StreamConnection) {
        private var closed = false
        fun send(bytes: ByteArray) {
            check()
            s.hostCall { conn.send(bytes) }
            s.addTransfer(bytes.size.toLong())
        }
        fun recv(o: Opts): Map<String, Any?> {
            check()
            val reply = s.hostCall {
                conn.recv(o.int("maxBytes", ScriptCaps.DEFAULT_TCP_MAX_BYTES, 1, ScriptCaps.MAX_ARRAY_LENGTH),
                    o.int("timeoutMs", ScriptCaps.DEFAULT_HTTP_TIMEOUT_MS, ScriptCaps.MIN_TIMEOUT_MS, ScriptCaps.MAX_TIMEOUT_MS), o.bytes("readUntil")?.takeIf { it.isNotEmpty() })
            }
            s.addTransfer(reply.bytes.size.toLong())
            return streamResult(reply)
        }
        fun close() { if (!closed) { closed = true; runCatching { conn.close() }; s.untrack(conn); s.closeHandle() } }
        private fun check() { s.ensureAlive(); if (closed) throw ScriptHostError(ScriptCopy.HANDLE_CLOSED) }
    }

    fun tcpConnect(o: Opts): Handle {
        val call = streamCall(o, false)
        val host = o.string("host")
        s.chargeCalls(1); s.gateWait()
        if (!s.openHandle()) { s.closeHandle(); throw ScriptHostError(ScriptCopy.TOO_MANY_HANDLES) }
        try {
            val to = gate.clearConnect(host, call.port)
            val conn = s.hostCall { transport.connect(to, call, s) }
            s.track(conn)
            return Handle(conn)
        } catch (t: Throwable) { s.closeHandle(); throw t }
    }

    fun udpExchange(o: Opts): Map<String, Any?> {
        val port = o.requiredInt("port", 1, PORT_MAX)
        val send = o.bytes("send") ?: ByteArray(0)
        val call = DatagramCall(port, send, o.int("timeoutMs", ScriptCaps.DEFAULT_HTTP_TIMEOUT_MS, ScriptCaps.MIN_TIMEOUT_MS, ScriptCaps.MAX_TIMEOUT_MS),
            o.int("maxBytes", ScriptCaps.DEFAULT_TCP_MAX_BYTES, 1, ScriptCaps.MAX_ARRAY_LENGTH))
        val host = o.string("host")
        s.chargeCalls(1); s.gateWait()
        val to = gate.clearConnect(host, port)
        val reply = s.hostCall { transport.datagram(to, call, s) }
        s.addTransfer(send.size.toLong() + (reply.bytes?.size ?: 0))
        val bytes = reply.bytes ?: ByteArray(0)
        return mapOf("bytes" to bytes, "text" to Bytes.toUtf8(bytes), "timedOut" to (reply.bytes == null), "timeMs" to reply.timeMs.toDouble())
    }

    fun certificate(host: String, port: Int, sni: String?): Map<String, Any?> {
        s.chargeCalls(1); s.gateWait()
        val to = gate.clearConnect(host, port)
        val chain = s.hostCall { transport.certificate(to, port, sni ?: sniFor(host), TLS_TIMEOUT_MS, s) }
        val certs = chain.certificates.map(::describe)
        return mapOf("protocol" to chain.protocol, "cipherSuite" to chain.cipherSuite, "leaf" to certs.firstOrNull(), "chain" to certs)
    }

    private fun describe(c: CertFacts): Map<String, Any?> = mapOf(
        "subject" to c.subject, "issuer" to c.issuer, "sans" to c.sans, "notBefore" to c.notBefore.toDouble(), "notAfter" to c.notAfter.toDouble(),
        "keyAlgorithm" to c.keyAlgorithm, "keyBits" to c.keyBits?.toDouble(), "signatureAlgorithm" to c.signatureAlgorithm, "serial" to c.serial,
        "sha256" to c.sha256, "sha1" to c.sha1, "selfSigned" to c.selfSigned,
    )

    // dns

    fun lookup(name: String): List<String> {
        s.chargeCalls(1); s.gateWait()
        val cleared = gate.clearName(name)
        return s.hostCall { transport.resolve(cleared, s) }.map { it.hostAddress.orEmpty().substringBefore('%') }
    }

    fun reverse(ip: String): String? {
        s.chargeCalls(1); s.gateWait()
        val cleared = gate.clearReverse(ip)
        return s.hostCall { transport.reverse(cleared, s) }
    }

    companion object {
        private const val MAX_METHOD = 16
        private const val PORT_MAX = 65_535
        private const val SPACE = 0x20
        private const val DEL = 0x7f
        private const val ECHO = 40
        private const val AWAIT_SLICE_MS = 50L
        private const val TLS_TIMEOUT_MS = 5_000
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val FORBIDDEN_HEADERS = setOf("content-length", "transfer-encoding", "connection")
        private const val TOKEN_PUNCTUATION = "!#$%&'*+-.^_`|~"
        private fun isToken(s: String) = s.isNotEmpty() && s.length <= ECHO && s.all { it.isLetterOrDigit() && it.code < DEL || it in TOKEN_PUNCTUATION }
        private val poolCount = AtomicInteger(0)
        private val POOL_THREADS = ThreadFactory { r -> Thread(null, r, "netprobe-script-many-${poolCount.incrementAndGet()}", ScriptCaps.SCRIPT_THREAD_STACK_BYTES).apply { isDaemon = true } }
    }
}
