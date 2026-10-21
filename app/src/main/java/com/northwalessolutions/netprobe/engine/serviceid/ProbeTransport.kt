package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.common.HttpClient
import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.common.TlsFailures
import com.northwalessolutions.netprobe.engine.common.closeOnCancel
import kotlinx.coroutines.CancellationException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/** How one connection attempt ended. Only [REFUSED] and [UNREACHABLE] mean the port is not there to be probed any more. */
enum class ConnectOutcome { CONNECTED, REFUSED, TIMEOUT, UNREACHABLE, TLS_FAILED }

/**
 * One connection to one port, the only way the probe ladder touches the network (Phase 3E spec 10). Every suspending call is
 * cancellation-aware: cancelling the caller closes the socket at once, even inside a TLS handshake. One transport is one
 * connection; the ladder asks its factory for a new one per probe.
 */
interface ProbeTransport {
    suspend fun connect(ip: InetAddress, port: Int, timeoutMs: Int): ConnectOutcome

    /** Connect and complete a trust-all TLS handshake (identification only). [ConnectOutcome.CONNECTED] means the handshake finished. */
    suspend fun connectTls(ip: InetAddress, port: Int, timeoutMs: Int): ConnectOutcome

    /** The leaf certificate's names after a successful [connectTls]. */
    fun peerCert(): CertInfo?

    suspend fun write(bytes: ByteArray)

    /**
     * Reads at most [maxBytes], waiting at most [waitMs] in all. Returns null when nothing arrived. Once bytes have arrived it
     * stops when the peer goes quiet, closes, or [stopAtNewline] sees a line end. Never returns more than [maxBytes].
     */
    suspend fun read(maxBytes: Int, waitMs: Int, stopAtNewline: Boolean = false): ByteArray?

    /** True once a read saw the peer close its side. */
    val peerClosed: Boolean

    fun close()
}

/** The real sockets. TLS uses the same trust-all context as the other read-only TLS tools. */
class SocketTransport : ProbeTransport {
    @Volatile private var raw: Socket? = null
    @Volatile private var live: Socket? = null
    @Volatile private var cert: CertInfo? = null
    @Volatile override var peerClosed: Boolean = false
        private set

    override suspend fun connect(ip: InetAddress, port: Int, timeoutMs: Int): ConnectOutcome =
        when (val o = Probe.connect(ip, port, timeoutMs, keepOpen = true)) {
            is Probe.Outcome.Connected -> {
                val s = o.socket
                if (s == null) ConnectOutcome.TIMEOUT else { raw = s; live = s; ConnectOutcome.CONNECTED }
            }
            Probe.Outcome.Refused -> ConnectOutcome.REFUSED
            Probe.Outcome.Timeout -> ConnectOutcome.TIMEOUT
            Probe.Outcome.Unreachable -> ConnectOutcome.UNREACHABLE
        }

    override suspend fun connectTls(ip: InetAddress, port: Int, timeoutMs: Int): ConnectOutcome {
        val first = connect(ip, port, timeoutMs)
        if (first != ConnectOutcome.CONNECTED) return first
        val plain = raw ?: return ConnectOutcome.TIMEOUT
        return try {
            closeOnCancel({ close() }) {
                val s = HttpClient.trustAllContext().socketFactory.createSocket(plain, ip.hostAddress, port, true) as SSLSocket
                live = s   // before the handshake, so Stop can close the socket the handshake is blocked on
                runCatching { s.enabledProtocols = s.supportedProtocols }
                runCatching { s.enabledCipherSuites = s.supportedCipherSuites }
                s.soTimeout = timeoutMs
                s.startHandshake()
                cert = certOf(s)
                ConnectOutcome.CONNECTED
            }
        } catch (e: CancellationException) { close(); throw e
        } catch (e: Exception) {
            close()
            if (TlsFailures.isTimeout(e)) ConnectOutcome.TIMEOUT else ConnectOutcome.TLS_FAILED
        }
    }

    private fun certOf(s: SSLSocket): CertInfo {
        val leaf = s.session.peerCertificates.firstOrNull() as? X509Certificate ?: return CertInfo(null, null, emptyList())
        val subject = leaf.subjectX500Principal.name
        val cn = TlsPeek.subjectField(subject, "CN"); val org = TlsPeek.subjectField(subject, "O")
        val sans = runCatching { leaf.subjectAlternativeNames?.mapNotNull { e -> e.getOrNull(1)?.toString() } }.getOrNull().orEmpty()
        return CertInfo(cn, org, (listOfNotNull(cn) + sans).distinct())
    }

    override fun peerCert(): CertInfo? = cert

    override suspend fun write(bytes: ByteArray) {
        val s = live ?: return
        closeOnCancel({ close() }) { s.getOutputStream().apply { write(bytes); flush() } }
    }

    override suspend fun read(maxBytes: Int, waitMs: Int, stopAtNewline: Boolean): ByteArray? {
        val s = live ?: return null
        return try {
            closeOnCancel({ close() }) { readBlocking(s, maxBytes, waitMs, stopAtNewline) }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { peerClosed = true; null }
    }

    private fun readBlocking(s: Socket, maxBytes: Int, waitMs: Int, stopAtNewline: Boolean): ByteArray? {
        val buf = ByteArray(maxBytes); var n = 0
        val input = s.getInputStream()
        val deadline = System.nanoTime() + waitMs * NANOS_PER_MS
        while (n < maxBytes) {
            val remainingMs = (deadline - System.nanoTime()) / NANOS_PER_MS
            if (remainingMs <= 0) break
            s.soTimeout = (if (n > 0) minOf(remainingMs, SETTLE_MS) else remainingMs).toInt().coerceAtLeast(1)
            val r = try { input.read(buf, n, maxBytes - n) } catch (_: SocketTimeoutException) { break }
            if (r < 0) { peerClosed = true; break }
            val from = n; n += r
            if (stopAtNewline && (from until n).any { buf[it] == NEWLINE }) break
        }
        return if (n == 0) null else buf.copyOf(n)
    }

    override fun close() {
        runCatching { live?.close() }
        runCatching { raw?.close() }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        /** After the first bytes, a quiet gap this long ends the read: a reply is over when the peer stops sending. */
        const val SETTLE_MS = 150L
        const val NEWLINE = '\n'.code.toByte()
    }
}
