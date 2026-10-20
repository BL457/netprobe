package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.engine.tools.ToolTarget

private const val UNRESERVED_EXTRA = "-._~"
private const val HEX = "0123456789ABCDEF"
private fun unreserved(b: Int): Boolean = (b in 'A'.code..'Z'.code) || (b in 'a'.code..'z'.code) || (b in '0'.code..'9'.code) || UNRESERVED_EXTRA.indexOf(b.toChar()) >= 0
private fun isHex(b: Byte): Boolean { val c = (b.toInt() and 0xFF).toChar(); return c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }

/** Percent-encodes every byte outside the RFC 3986 unreserved set, '/' and an existing valid %XX escape, as UTF-8 (spec 5.2). */
fun encodePath(word: String): String {
    val bytes = word.toByteArray(Charsets.UTF_8); val sb = StringBuilder(); var i = 0
    while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        when {
            b == '/'.code || unreserved(b) -> sb.append(b.toChar())
            b == '%'.code && i + 2 < bytes.size && isHex(bytes[i + 1]) && isHex(bytes[i + 2]) -> sb.append('%')
            else -> sb.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
        }
        i++
    }
    return sb.toString()
}
/** 12 random lowercase letters and digits (spec 5.4.1). */
fun randomToken(): String { val a = "abcdefghijklmnopqrstuvwxyz0123456789"; return (1..12).map { a.random() }.joinToString("") }
fun urlOf(target: ToolTarget, path: String): String = (if (target.tls) "https" else "http") + "://" + target.ip.hostAddress + ":" + target.port + path
