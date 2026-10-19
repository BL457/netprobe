package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Proxy
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.*

data class HttpPeek(val title: String?, val server: String?, val authRealm: String?, val status: Int)

object HttpTitleParser {
    private const val MAX_TITLE_CHARS = 120
    private val titleRe = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val whitespaceRe = Regex("[ \t\r\n\u000B\u000C]+")
    private val realmRe = Regex("realm=\"?([^\",]+)\"?", RegexOption.IGNORE_CASE)
    // Order matters: &amp; must be decoded LAST so "&amp;lt;" becomes "&lt;" and not "<".
    private val entities = linkedMapOf("&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ", "&amp;" to "&")

    fun title(html: String): String? {
        var t = titleRe.find(html)?.groupValues?.get(1) ?: return null
        entities.forEach { (k, v) -> t = t.replace(k, v, ignoreCase = true) }
        t = t.replace(whitespaceRe, " ").trim()
        return t.takeIf { it.isNotEmpty() }?.take(MAX_TITLE_CHARS)
    }

    fun realm(wwwAuthenticate: String?): String? =
        wwwAuthenticate?.let { realmRe.find(it)?.groupValues?.get(1)?.trim() }
}

class HttpTitleFetcher(private val timeoutMs: Int = 2000, private val maxBytes: Int = 65536) {
    private companion object {
        const val READ_BUFFER_BYTES = 8192
        const val HTTP_REDIRECT_MIN = 300
        const val HTTP_REDIRECT_MAX = 399
        const val HTTP_ERROR_MIN = 400
    }

    // Trust-all is used ONLY to read the page for identification; nothing sensitive is sent.
    private val trustAll: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>() }), null) }.socketFactory
    }

    suspend fun fetch(ip: Inet4Address, port: Int, tls: Boolean): HttpPeek? {
        return try {
            runInterruptible(Dispatchers.IO) { fetchBlocking(ip, port, tls) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    private fun fetchBlocking(ip: Inet4Address, port: Int, tls: Boolean): HttpPeek {
        var url = URL(if (tls) "https" else "http", ip.hostAddress, port, "/")
        var hops = 0
        while (true) {
            // NO_PROXY: a LAN fetch must never be handed to a Wi-Fi proxy or PAC outside the network.
            val c = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                if (c is HttpsURLConnection) { c.sslSocketFactory = trustAll; c.hostnameVerifier = HostnameVerifier { _, _ -> true } }
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "NetProbe/0.1"); c.setRequestProperty("Accept", "text/html")
                val status = c.responseCode
                val loc = c.getHeaderField("Location")
                if (status in HTTP_REDIRECT_MIN..HTTP_REDIRECT_MAX && loc != null && hops == 0) {
                    val next = runCatching { URL(url, loc) }.getOrNull()
                    if (next != null && next.host == url.host) { url = next; hops++; continue }
                }
                val bytes = ByteArrayOutputStream()
                // Overall deadline stops a device dripping bytes just inside the per-read timeout.
                val deadline = System.currentTimeMillis() + timeoutMs
                // A body-read failure (e.g. timeout) must not discard the status/headers already received.
                try {
                    val stream = (if (status >= HTTP_ERROR_MIN) c.errorStream else c.inputStream) ?: c.errorStream
                    stream?.use { ins ->
                        val buf = ByteArray(READ_BUFFER_BYTES)
                        while (bytes.size() < maxBytes && System.currentTimeMillis() <= deadline) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            bytes.write(buf, 0, minOf(n, maxBytes - bytes.size()))
                        }
                    }
                } catch (_: IOException) {
                    // keep whatever bytes arrived
                }
                val html = String(bytes.toByteArray(), Charsets.UTF_8)
                return HttpPeek(HttpTitleParser.title(html), c.getHeaderField("Server"), HttpTitleParser.realm(c.getHeaderField("WWW-Authenticate")), status)
            } finally {
                c.disconnect()
            }
        }
    }
}
