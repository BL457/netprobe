package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.ui.tools.ScriptCopy

/**
 * A strict http or https URL (G2). Parsed by hand so that no lenient parser can disagree with the scope check about which host is meant:
 * no user info, no backslashes, no spaces or control characters, a decimal port, a fragment dropped. The host is returned as written
 * (lower case); the scope guard decides whether it is a name, a canonical address or a refused literal.
 */
internal data class ScriptUrl(val tls: Boolean, val host: String, val port: Int, val pathAndQuery: String) {
    val hostHeader: String get() {
        val name = if (host.contains(':')) "[$host]" else host
        return if (port == defaultPort(tls)) name else "$name:$port"
    }

    val text: String get() {
        val name = if (host.contains(':')) "[$host]" else host
        return (if (tls) "https://" else "http://") + name + (if (port == defaultPort(tls)) "" else ":$port") + pathAndQuery
    }

    /** The URL a redirect's Location points to, resolved against this one with the same strictness. */
    fun resolve(location: String): ScriptUrl {
        val loc = location.trim()
        return when {
            loc.startsWith("http://", ignoreCase = true) || loc.startsWith("https://", ignoreCase = true) -> parse(loc)
            loc.startsWith("//") -> parse((if (tls) "https:" else "http:") + loc)
            loc.startsWith("/") -> copy(pathAndQuery = cleanPath(loc))
            loc.startsWith("?") -> copy(pathAndQuery = cleanPath(pathAndQuery.substringBefore('?').substringBefore('#') + loc))
            loc.isEmpty() -> this
            loc.contains("://") -> throw ScriptHostError(ScriptCopy.badUrl(loc.take(ECHO)))
            else -> copy(pathAndQuery = cleanPath(pathAndQuery.substringBefore('?').substringBeforeLast('/') + "/" + loc))
        }
    }

    companion object {
        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443
        private const val ECHO = 60
        private const val MAX_PORT = 65_535
        private const val FIRST_VISIBLE = 0x21
        private const val DEL = 0x7f
        private const val BACKSLASH = 0x5c
        fun defaultPort(tls: Boolean) = if (tls) HTTPS_PORT else HTTP_PORT

        fun parse(raw: String): ScriptUrl {
            if (raw.length > ScriptCaps.MAX_URL_CHARS) throw ScriptHostError(ScriptCopy.badUrl(raw.take(ECHO)))
            val tls = when {
                raw.startsWith("https://", ignoreCase = true) -> true
                raw.startsWith("http://", ignoreCase = true) -> false
                else -> throw ScriptHostError(ScriptCopy.URL_NOT_HTTP)
            }
            val rest = raw.substring(if (tls) 8 else 7)
            val authEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authEnd)
            if (authority.isEmpty()) throw ScriptHostError(ScriptCopy.badUrl(raw.take(ECHO)))
            if (authority.any { it == '@' || it == '\\' || it.code < FIRST_VISIBLE || it.code >= DEL }) throw ScriptHostError(ScriptCopy.URL_AUTHORITY_REFUSED)
            val host: String
            val portText: String?
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) throw ScriptHostError(ScriptCopy.badUrl(raw.take(ECHO)))
                host = authority.substring(1, close)
                val after = authority.substring(close + 1)
                portText = when { after.isEmpty() -> null; after.startsWith(":") -> after.substring(1); else -> throw ScriptHostError(ScriptCopy.badUrl(raw.take(ECHO))) }
            } else {
                val colon = authority.lastIndexOf(':')
                host = if (colon < 0) authority else authority.substring(0, colon)
                portText = if (colon < 0) null else authority.substring(colon + 1)
            }
            if (host.isEmpty()) throw ScriptHostError(ScriptCopy.badUrl(raw.take(ECHO)))
            val port = if (portText == null) defaultPort(tls) else {
                if (portText.isEmpty() || portText.length > 5 || !portText.all { it in '0'..'9' }) throw ScriptHostError(ScriptCopy.URL_PORT_BAD)
                portText.toInt().also { if (it < 1 || it > MAX_PORT) throw ScriptHostError(ScriptCopy.URL_PORT_BAD) }
            }
            val path = cleanPath(rest.substring(authEnd).substringBefore('#'))
            return ScriptUrl(tls, host.lowercase(), port, path)
        }

        /** Path and query: always starts with "/", no control characters or spaces, non-ASCII percent-encoded as UTF-8. */
        fun cleanPath(raw: String): String {
            val p = if (raw.isEmpty()) "/" else if (raw.startsWith("?")) "/$raw" else raw
            val sb = StringBuilder(p.length)
            var i = 0
            while (i < p.length) {
                val cp = p.codePointAt(i)
                i += Character.charCount(cp)
                when {
                    cp < FIRST_VISIBLE || cp == DEL || cp == BACKSLASH -> throw ScriptHostError(ScriptCopy.URL_PATH_BAD)
                    cp > DEL -> for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) sb.append('%').append("%02X".format(b.toInt() and 0xff))
                    else -> sb.append(cp.toChar())
                }
            }
            return if (sb.startsWith("/")) sb.toString() else "/$sb"
        }
    }
}
