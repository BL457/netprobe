package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.common.CancellableIo
import com.northwalessolutions.netprobe.engine.common.HttpClient
import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.common.bindOrFail
import com.northwalessolutions.netprobe.engine.common.closeOnCancel
import com.northwalessolutions.netprobe.engine.common.currentBinder
import kotlinx.coroutines.CancellationException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** The leaf certificate's subject CN and O, and CN plus SANs for the "cert ..." detail (spec 3.1). */
data class CertInfo(val cn: String?, val org: String?, val names: List<String>)

/** One handshake as the TLS certificate tool reads it (spec 6.2): protocol, suite, the whole peer chain and the SNI name sent. */
data class TlsDetails(val protocol: String, val cipherSuite: String, val chain: List<X509Certificate>, val serverNameSent: String?)

/** Reads certificate names for identification only. Trust-all is never used for the browser launch. */
open class TlsPeek {
    open suspend fun certInfo(ip: InetAddress, port: Int, timeoutMs: Int = TIMEOUT_MS): CertInfo? {
        val route = currentBinder()   // an off-subnet port scan's bound network (final review I2); a failed bind fails closed (null)
        val s = try { factory.createSocket() as SSLSocket } catch (_: Exception) { return null }
        return try {
            closeOnCancel({ s.close() }) {
                route?.bindOrFail(s)
                s.connect(InetSocketAddress(ip, port), timeoutMs); s.soTimeout = timeoutMs
                s.startHandshake()
                val cert = s.session.peerCertificates.firstOrNull() as? X509Certificate
                if (cert == null) CertInfo(null, null, emptyList()) else {
                    val subject = cert.subjectX500Principal.name
                    val cn = subjectField(subject, "CN"); val org = subjectField(subject, "O")
                    val sans = runCatching { cert.subjectAlternativeNames?.mapNotNull { e -> e.getOrNull(1)?.toString() } }.getOrNull().orEmpty()
                    CertInfo(cn, org, (listOfNotNull(cn) + sans).distinct())
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { null
        } finally { runCatching { s.close() } }
    }

    /** Outcome of one handshake attempt: only a failed handshake (the name may not suit the device) is worth a retry. */
    private sealed class Attempt {
        class Done(val details: TlsDetails) : Attempt()
        object HandshakeFailed : Attempt()
        object NoConnection : Attempt()
    }

    /**
     * Named handshake first, then once without a name if the handshake itself failed (spec 4.9). A refused, reset or timed-out
     * connection or handshake is never retried: a dead host costs one wait, not two. Null when no handshake completes.
     */
    open suspend fun details(ip: InetAddress, port: Int, serverName: String?, timeoutMs: Int = TLS_TOOL_TIMEOUT_MS): TlsDetails? {
        val context = HttpClient.trustAllContext()   // one SSLContext per run
        val named = attemptDetails(context, ip, port, serverName, timeoutMs)
        if (named is Attempt.Done) return named.details
        if (named !is Attempt.HandshakeFailed || serverName == null) return null
        return (attemptDetails(context, ip, port, null, timeoutMs) as? Attempt.Done)?.details
    }

    private suspend fun attemptDetails(context: SSLContext, ip: InetAddress, port: Int, serverName: String?, timeoutMs: Int): Attempt {
        val connected = Probe.connect(ip, port, timeoutMs, keepOpen = true) as? Probe.Outcome.Connected ?: return Attempt.NoConnection
        val raw = connected.socket ?: return Attempt.NoConnection
        return try {
            // SNI is the name when given; an IP literal sends no SNI extension at all.
            val s = context.socketFactory.createSocket(raw, serverName ?: ip.hostAddress, port, true) as SSLSocket
            runCatching { s.enabledProtocols = s.supportedProtocols }
            runCatching { s.enabledCipherSuites = s.supportedCipherSuites }
            CancellableIo.closing(s) {
                s.soTimeout = timeoutMs   // before the handshake: a silent peer must not block forever
                s.startHandshake()
                val chain = s.session.peerCertificates.filterIsInstance<X509Certificate>()
                if (chain.isEmpty()) Attempt.HandshakeFailed else Attempt.Done(TlsDetails(s.session.protocol, s.session.cipherSuite, chain, serverName))
            }
        } catch (e: CancellationException) { throw e
        } catch (_: SocketTimeoutException) { Attempt.NoConnection
        } catch (_: SSLException) { Attempt.HandshakeFailed
        } catch (_: Exception) { Attempt.NoConnection
        } finally { runCatching { raw.close() } }
    }

    companion object {
        const val TIMEOUT_MS = 1500
        const val TLS_TOOL_TIMEOUT_MS = 3000
        private const val MAX_VALUE = 120
        private val unescape = Regex("\\\\(.)")
        private val factory: SSLSocketFactory by lazy {
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>() }), null) }.socketFactory
        }
        /** One attribute of an RFC 2253 subject ("CN=a,O=Ubiquiti\, Inc.,C=US"); escaped characters stay in the value; trimmed, at most 120 characters. */
        fun subjectField(x500: String, key: String): String? =
            Regex("(?:^|,)\\s*$key=((?:\\\\.|[^,\\\\])+)").find(x500)?.groupValues?.get(1)
                ?.replace(unescape, "\$1")?.trim()?.take(MAX_VALUE)?.ifEmpty { null }
    }
}
