package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.common.bindOrFail
import com.northwalessolutions.netprobe.engine.common.closeOnCancel
import com.northwalessolutions.netprobe.engine.common.currentBinder
import kotlinx.coroutines.CancellationException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** The leaf certificate's subject CN and O, and CN plus SANs for the "cert ..." detail (spec 3.1). */
data class CertInfo(val cn: String?, val org: String?, val names: List<String>)

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

    companion object {
        const val TIMEOUT_MS = 1500
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
