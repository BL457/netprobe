package com.northwalessolutions.netprobe.engine.serviceid

object BannerParsers {
    private const val MAX_HEADER = 120
    const val OPENSSH_WINDOWS = "OpenSSH_for_Windows"
    private val versionRe = Regex("(\\d+(?:\\.\\d+)+[A-Za-z0-9]*)")

    fun ssh(line: String): ServiceInfo? {
        val l = line.trim()
        if (!l.startsWith("SSH-")) return null
        val rest = l.removePrefix("SSH-").substringAfter('-', "")  // after protocol version
        if (rest.isEmpty()) return ServiceInfo("SSH", confidence = Confidence.CONFIRMED)
        val soft = rest.substringBefore(' ')
        val comment = rest.substringAfter(' ', "").ifBlank { null }
        // Microsoft's port announces itself as "OpenSSH_for_Windows_8.1"; split on the last underscore of that prefix, not the first.
        val windows = soft.startsWith(OPENSSH_WINDOWS + "_")
        val product = if (windows) OPENSSH_WINDOWS else soft.substringBefore('_').ifBlank { null }
        val version = (if (windows) soft.removePrefix(OPENSSH_WINDOWS + "_") else soft.substringAfter('_', "")).ifBlank { null }
        return ServiceInfo("SSH", product, version, comment, Confidence.CONFIRMED)
    }

    private val knownProducts = listOf("ProFTPD", "vsFTPd", "Pure-FTPd", "FileZilla", "Postfix", "Exim", "Sendmail", "Dovecot", "Courier", "Microsoft ESMTP", "Exchange", "Cyrus", "Synology")
    fun greeting(line: String, portName: String): ServiceInfo? {
        val l = line.trim()
        if (l.isEmpty() || !(l.startsWith("220") || l.startsWith("+OK") || l.startsWith("* OK") || l.startsWith("200"))) return null
        val product = knownProducts.firstOrNull { l.contains(it, ignoreCase = true) }
        val version = product?.let { Regex(Regex.escape(it) + "[ /_-]*v?" + versionRe.pattern, RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1) }
        return ServiceInfo(portName, product, version, l.take(120), Confidence.CONFIRMED)
    }

    fun serverHeader(value: String): Pair<String?, String?> {
        val v = value.trim()
        if (v.isEmpty()) return null to null
        val first = v.substringBefore(' ')
        val product = first.substringBefore('/').ifBlank { null }
        val version = first.substringAfter('/', "").ifBlank { null }
        return product to version
    }

    fun httpHead(response: String): ServiceInfo? {
        val lines = response.split("\r\n", "\n")
        val status = lines.firstOrNull()?.takeIf { it.startsWith("HTTP/") && it.length > 9 } ?: return null
        val headers = lines.drop(1).takeWhile { it.isNotBlank() }
            .mapNotNull { h -> h.indexOf(':').takeIf { it > 0 }?.let { h.substring(0, it).trim().lowercase() to h.substring(it + 1).trim() } }.toMap()
        val server = headers["server"]?.take(MAX_HEADER)?.trim()?.ifEmpty { null }
        val (product, version) = server?.let { serverHeader(it) } ?: (null to null)
        val realm = headers["www-authenticate"]?.let { Regex("realm=\"?([^\"]+)\"?").find(it)?.groupValues?.get(1) }?.take(MAX_HEADER)?.trim()?.ifEmpty { null }
        val detail = listOfNotNull(status.substringAfter(' ').take(40), realm?.let { "realm $it" }).joinToString(", ")
        return ServiceInfo("HTTP", product, version, detail, Confidence.CONFIRMED,
            server = server, realm = realm, poweredBy = headers["x-powered-by"]?.take(MAX_HEADER)?.ifEmpty { null })
    }

    /** "RTSP/1.0 ..." confirms RTSP; its Server header fills product and version and is kept whole in server. */
    fun rtspReply(text: String): ServiceInfo? {
        val lines = text.split("\r\n", "\n")
        if (lines.firstOrNull()?.startsWith("RTSP/1.0") != true) return null
        val server = lines.drop(1).takeWhile { it.isNotBlank() }
            .firstOrNull { it.substringBefore(':').trim().equals("server", ignoreCase = true) }
            ?.substringAfter(':', "")?.trim()?.take(MAX_HEADER)?.ifEmpty { null }
        val (product, version) = server?.let { serverHeader(it) } ?: (null to null)
        return ServiceInfo("RTSP", product, version, null, Confidence.CONFIRMED, server = server)
    }
    fun classifyUnknown(bytes: ByteArray, portName: String): ServiceInfo {
        val text = runCatching { String(bytes, Charsets.ISO_8859_1) }.getOrDefault("")
        if (text.startsWith("SSH-")) return ssh(text.lineSequence().first()) ?: ServiceInfo("SSH", confidence = Confidence.CONFIRMED)
        if (text.startsWith("HTTP/")) return httpHead(text) ?: ServiceInfo("HTTP", confidence = Confidence.CONFIRMED)
        val printable = bytes.isNotEmpty() && bytes.all { b -> val c = b.toInt() and 0xff; c == 9 || c == 10 || c == 13 || c in 32..126 }
        val excerpt = if (printable) text.lineSequence().firstOrNull()?.trim()?.take(120)?.ifBlank { null } else null
        return ServiceInfo(portName, detail = excerpt, confidence = if (excerpt != null) Confidence.CONFIRMED else Confidence.PORT_TABLE)
    }
}
