package com.northwalessolutions.netprobe.engine.serviceid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Virtual time for the ladder tests: the fake transport advances it instead of sleeping. */
class FakeClock {
    @Volatile var now = 0L
    fun advance(ms: Long) { now += ms }
}

/** What the scripted peer does with one read: [bytes] null is silence; [delayMs] is how long it takes; [closeAfter] closes after answering. */
class Reply(val bytes: ByteArray?, val delayMs: Long = 0, val closeAfter: Boolean = false) {
    companion object {
        val SILENT = Reply(null)
        /** Accepts the connection and closes it before any byte. */
        val ACCEPT_THEN_CLOSE = Reply(null, closeAfter = true)
        fun text(s: String, delayMs: Long = 0) = Reply(s.toByteArray(Charsets.ISO_8859_1), delayMs)
    }
}

/**
 * A scripted network: one [FakeTransport] per connection, all sharing this script, log and clock. No sockets, no sleeping.
 * [respond] gets the port, whether the connection is TLS, and what the ladder wrote (null for a read with nothing written).
 */
class FakeNetwork(val clock: FakeClock = FakeClock()) {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val connects = AtomicInteger()
    val handshakes = AtomicInteger()
    val closes = AtomicInteger()
    var connectOutcome: (port: Int, tls: Boolean) -> ConnectOutcome = { _, _ -> ConnectOutcome.CONNECTED }
    var cert: (port: Int) -> CertInfo? = { null }
    var respond: (port: Int, tls: Boolean, sent: String?) -> Reply = { _, _, _ -> Reply.SILENT }
    var connectCostMs = 0L
    var handshakeCostMs = 0L
    /** When true a TLS handshake suspends until cancelled (Stop during a handshake). */
    var hangHandshake = false
    @Volatile var handshakeEntered = false
    fun transport(): ProbeTransport = FakeTransport(this)
    /** Every connect, in order, as "connect:port" or "tls:port". */
    fun connectLog(): List<String> = events.filter { it.startsWith("connect:") || it.startsWith("tls:") }
    /** Every write, in order, as "port[:tls]:first bytes". */
    fun sent(): List<String> = events.filter { it.startsWith("send:") }.map { it.removePrefix("send:") }
}

class FakeTransport(private val net: FakeNetwork) : ProbeTransport {
    private var port = 0
    private var tls = false
    private var written: String? = null
    private var open = false
    private var cert: CertInfo? = null
    override var peerClosed: Boolean = false
        private set

    override suspend fun connect(ip: InetAddress, port: Int, timeoutMs: Int): ConnectOutcome {
        this.port = port; net.connects.incrementAndGet(); net.events += "connect:$port"; net.clock.advance(net.connectCostMs)
        val o = net.connectOutcome(port, false)
        open = o == ConnectOutcome.CONNECTED
        return o
    }

    override suspend fun connectTls(ip: InetAddress, port: Int, timeoutMs: Int): ConnectOutcome {
        this.port = port; net.connects.incrementAndGet(); net.events += "tls:$port"; net.clock.advance(net.connectCostMs)
        val o = net.connectOutcome(port, true)
        if (o != ConnectOutcome.CONNECTED) return o
        open = true
        try {
            net.handshakeEntered = true
            if (net.hangHandshake) awaitCancellation()
        } catch (e: CancellationException) { close(); throw e }
        net.handshakes.incrementAndGet(); net.clock.advance(net.handshakeCostMs)
        val c = net.cert(port)
        if (c == null) { close(); return ConnectOutcome.TLS_FAILED }
        tls = true; cert = c
        return ConnectOutcome.CONNECTED
    }

    override fun peerCert(): CertInfo? = cert

    override suspend fun write(bytes: ByteArray) {
        written = String(bytes, Charsets.ISO_8859_1)
        net.events += "send:$port${if (tls) ":tls" else ""}:${written!!.take(14).replace("\r", "\\r").replace("\n", "\\n")}"
    }

    override suspend fun read(maxBytes: Int, waitMs: Int, stopAtNewline: Boolean): ByteArray? {
        val r = net.respond(port, tls, written)
        val b = r.bytes
        if (b == null || r.delayMs > waitMs) {
            net.clock.advance(waitMs.toLong())
            if (r.closeAfter) peerClosed = true
            return null
        }
        net.clock.advance(r.delayMs)
        if (r.closeAfter) peerClosed = true
        // A real socket hands over the whole segment that carried the newline, so stopAtNewline does not cut the scripted bytes.
        return b.copyOf(minOf(b.size, maxBytes))
    }

    override fun close() {
        if (open) { open = false; net.closes.incrementAndGet(); net.events += "close:$port" }
    }
}
