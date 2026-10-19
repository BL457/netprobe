package com.northwalessolutions.netprobe.engine.serviceid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** Reads certificate names for identification only. Trust-all is never used for the browser launch. */
open class TlsPeek {
    open suspend fun certNames(ip: InetAddress, port: Int, timeoutMs: Int = 1500): List<String>? = try {
        runInterruptible(Dispatchers.IO) {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }), null)
            (ctx.socketFactory.createSocket() as SSLSocket).use { s ->
                s.connect(InetSocketAddress(ip, port), timeoutMs); s.soTimeout = timeoutMs
                s.startHandshake()
                val cert = s.session.peerCertificates.firstOrNull() as? X509Certificate ?: return@runInterruptible emptyList()
                val cn = Regex("CN=([^,]+)").find(cert.subjectX500Principal.name)?.groupValues?.get(1)
                val sans = runCatching { cert.subjectAlternativeNames?.mapNotNull { e -> e.getOrNull(1)?.toString() } }.getOrNull().orEmpty()
                (listOfNotNull(cn) + sans).distinct()
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: InterruptedException) { throw kotlinx.coroutines.CancellationException() } catch (_: Exception) { null }
}
