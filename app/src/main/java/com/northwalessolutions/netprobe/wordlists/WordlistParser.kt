package com.northwalessolutions.netprobe.wordlists

import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class ParseResult(val entries: List<String>, val skipped: Int)

/** Streaming parse and normalisation of a wordlist (spec 5.6.4), used for imports and verified against the bundled lists. */
object WordlistParser {
    const val MAX_ENTRY_CHARS = 256

    fun parse(input: InputStream): ParseResult {
        val entries = LinkedHashSet<String>()
        var skipped = 0
        val bytes = input.readBytes()
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        var i = start
        val line = ArrayList<Byte>()
        fun flush() {
            if (line.isEmpty() && entries.isEmpty()) { /* nothing yet */ }
            val raw = line.toByteArray(); line.clear()
            val text = decodeUtf8(raw) ?: run { if (raw.isNotEmpty()) skipped++; return }
            var t = text.trim()
            if (t.isEmpty() || t.startsWith("#")) return
            t = t.trimStart('/')
            if (t.isEmpty()) return
            if (t.length > MAX_ENTRY_CHARS || t.any { it.isISOControl() }) { skipped++; return }
            entries.add(t)
        }
        while (i < bytes.size) {
            val b = bytes[i]
            when (b) {
                '\n'.code.toByte() -> { flush(); i++ }
                '\r'.code.toByte() -> { flush(); i++; if (i < bytes.size && bytes[i] == '\n'.code.toByte()) i++ }
                else -> { line.add(b); i++ }
            }
        }
        flush()
        return ParseResult(entries.toList(), skipped)
    }

    private fun decodeUtf8(raw: ByteArray): String? {
        if (raw.isEmpty()) return ""
        val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString() }.getOrNull()
    }
}
