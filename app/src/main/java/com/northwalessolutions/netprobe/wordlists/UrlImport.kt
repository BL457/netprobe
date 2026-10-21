package com.northwalessolutions.netprobe.wordlists

import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.Rfc1918
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.ui.wordlists.WordlistCopy
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** The URL dialog's check (3C spec 5.3): a URL ready to fetch, or the inline error. */
sealed interface UrlCheck {
    data class Ok(val url: URL) : UrlCheck
    data class Invalid(val error: String) : UrlCheck
}

sealed interface RedirectDecision {
    data class Follow(val url: URL) : RedirectDecision
    object TooMany : RedirectDecision
    object Insecure : RedirectDecision
    object LocalTarget : RedirectDecision
    /** No Location, an unusable one, another scheme, or credentials in it: the fetch fails with the server's status. */
    object Invalid : RedirectDecision
}

sealed interface FetchFailure {
    object UnknownHost : FetchFailure
    object NoAnswer : FetchFailure
    object Tls : FetchFailure
    object Connection : FetchFailure
    data class Status(val code: Int) : FetchFailure
    /** [type] is the refused media type, or null for the NUL-byte check. */
    data class NotText(val type: String?) : FetchFailure
    object Insecure : FetchFailure
    object LocalTarget : FetchFailure
    object TooManyRedirects : FetchFailure
}

sealed interface UrlImportResult {
    data class Stored(val result: ImportResult) : UrlImportResult
    data class Failed(val reason: FetchFailure) : UrlImportResult
    object Cancelled : UrlImportResult
}

/** Pure rules for the wordlist URL import (3C spec 5.3, D16). Nothing here opens a socket or resolves a name. */
object UrlImportRules {
    const val MAX_REDIRECTS = 3
    const val MAX_URL_CHARS = 2048
    const val BINARY_SNIFF_BYTES = 8_192
    private const val HTTP = "http"
    private const val HTTPS = "https"
    private const val SCHEME_SEPARATOR = "://"
    private const val TEXT_PREFIX = "text/"
    private const val HTML = "text/html"
    private const val OCTET_STREAM = "application/octet-stream"
    private const val ULA_MASK = 0xFE
    private const val ULA_PREFIX = 0xFC
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val LOOPBACK = SubnetMath.of(Ipv4.parse("127.0.0.0")!!, 8)
    private val LINK_LOCAL = SubnetMath.of(Ipv4.parse("169.254.0.0")!!, 16)
    private const val LOCALHOST = "localhost"
    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home.arpa")

    fun validate(text: String): UrlCheck {
        val t = text.trim()
        if (t.isEmpty()) return UrlCheck.Invalid(WordlistCopy.ERR_URL)
        if (t.length > MAX_URL_CHARS) return UrlCheck.Invalid(WordlistCopy.ERR_URL_LONG)
        if (t.any { it.isWhitespace() || it.isISOControl() }) return UrlCheck.Invalid(WordlistCopy.ERR_URL)
        val full = (if (hasScheme(t)) t else "https://$t").substringBefore('#')
        val uri = try { URI(full) } catch (_: URISyntaxException) { return UrlCheck.Invalid(WordlistCopy.ERR_URL) }
        val scheme = uri.scheme?.lowercase()
        if (scheme != HTTP && scheme != HTTPS) return UrlCheck.Invalid(WordlistCopy.ERR_URL)
        if (uri.rawUserInfo != null) return UrlCheck.Invalid(WordlistCopy.ERR_URL_USER)
        if (uri.host.isNullOrEmpty()) return UrlCheck.Invalid(WordlistCopy.ERR_URL)
        val url = try { uri.toURL() } catch (_: MalformedURLException) { return UrlCheck.Invalid(WordlistCopy.ERR_URL) } catch (_: IllegalArgumentException) { return UrlCheck.Invalid(WordlistCopy.ERR_URL) }
        if (url.toString().length > MAX_URL_CHARS) return UrlCheck.Invalid(WordlistCopy.ERR_URL_LONG)
        return UrlCheck.Ok(url)
    }

    /** "x://" is a scheme; so is an opaque "name:" (javascript:, data:) unless a port digit follows or an "@" shows user info. */
    private fun hasScheme(t: String): Boolean {
        if (SCHEME_SEPARATOR in t) return true
        val m = SCHEME.find(t) ?: return false
        val rest = t.substring(m.value.length)
        return rest.isNotEmpty() && !rest.first().isDigit() && '@' !in t
    }

    /** One hop of a 3xx (spec 5.3): [original] is the URL the Director typed, [from] the URL that answered, [hops] the redirects already followed. */
    fun redirect(original: URL, from: URL, location: String?, hops: Int): RedirectDecision {
        if (hops >= MAX_REDIRECTS) return RedirectDecision.TooMany
        val loc = location?.trim()?.takeIf { it.isNotEmpty() } ?: return RedirectDecision.Invalid
        val target = try { URL(from, loc) } catch (_: MalformedURLException) { return RedirectDecision.Invalid }
        val scheme = target.protocol.lowercase()
        if (scheme != HTTP && scheme != HTTPS) return RedirectDecision.Invalid
        if (target.host.isNullOrEmpty() || target.userInfo != null) return RedirectDecision.Invalid   // no credentials, ever
        if (from.protocol.equals(HTTPS, ignoreCase = true) && scheme == HTTP) return RedirectDecision.Insecure
        if (isLocalHost(target.host) && !isLocalHost(original.host)) return RedirectDecision.LocalTarget   // by literal or by name (plan review G4)
        return RedirectDecision.Follow(if (target.ref == null) target else URL(target.toString().substringBefore('#')))
    }

    fun contentType(header: String?): Boolean {
        val t = mediaType(header) ?: return true                       // no header: accepted, the NUL check still runs
        return t != HTML && (t.startsWith(TEXT_PREFIX) || t == OCTET_STREAM)
    }
    fun mediaType(header: String?): String? = header?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun looksBinary(prefix: ByteArray): Boolean {
        val n = minOf(prefix.size, BINARY_SNIFF_BYTES)
        for (i in 0 until n) if (prefix[i] == 0.toByte()) return true
        return false
    }

    /** The final URL's last path segment, percent-decoded, else its host; WordlistStore.cleanName then strips the extension. */
    fun displayName(url: URL): String? {
        val path = runCatching { url.toURI().path }.getOrNull() ?: url.path.orEmpty()   // toURI throws on characters a URI refuses: keep the raw path
        val segment = path.substringAfterLast('/').trim()
        return segment.ifEmpty { url.host?.takeIf { it.isNotEmpty() } }
    }

    fun failureOf(e: IOException): FetchFailure = when (e) {
        is UnknownHostException -> FetchFailure.UnknownHost
        is SocketTimeoutException -> FetchFailure.NoAnswer
        is SSLException -> FetchFailure.Tls
        else -> FetchFailure.Connection
    }

    /** A loopback, link-local or RFC1918 IPv4 literal, or an IPv6 loopback, link-local or unique-local literal. Names are never resolved. */
    internal fun isLocalLiteral(host: String?): Boolean {
        val h = host?.removePrefix("[")?.removeSuffix("]") ?: return false
        Ipv4.parse(h)?.let { return isLocalV4(it) }
        if (':' !in h) return false
        val a = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false   // an IPv6 literal: parsed, never looked up
        if (a is Inet4Address) return isLocalV4(a)                                      // ::ffff:10.0.0.5
        return a.isLoopbackAddress || a.isLinkLocalAddress || (a.address[0].toInt() and ULA_MASK) == ULA_PREFIX
    }
    private fun isLocalV4(a: Inet4Address): Boolean = LOOPBACK.contains(a) || LINK_LOCAL.contains(a) || Rfc1918.contains(a)

    /** Local by IP literal or by name (plan review G4). */
    fun isLocalHost(host: String?): Boolean = isLocalLiteral(host) || isLocalName(host)

    /** localhost, *.localhost, *.local (mDNS), *.lan, *.home.arpa and single-label names: never a public file host. Literals are isLocalLiteral's. */
    internal fun isLocalName(host: String?): Boolean {
        val h = host?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        if (h.startsWith("[") || ':' in h || Ipv4.parse(h) != null) return false
        return h == LOCALHOST || '.' !in h || LOCAL_SUFFIXES.any { h.endsWith(it) }
    }

    /** What a followed hop's name may not resolve to (plan review G4): loopback, link-local, RFC1918, IPv6 unique-local, or the any address. */
    fun isLocalAddress(a: InetAddress): Boolean = when (a) {
        is Inet4Address -> isLocalV4(a) || a.isAnyLocalAddress
        else -> a.isLoopbackAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || (a.address[0].toInt() and ULA_MASK) == ULA_PREFIX
    }
}
