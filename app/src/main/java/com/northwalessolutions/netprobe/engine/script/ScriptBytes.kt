package com.northwalessolutions.netprobe.engine.script

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest

/** Reading, decoding and hashing script files. Pure functions, no Android. */
object ScriptBytes {
    /** The most bytes an import reads from a file or a URL before giving up (B4 brief); the script itself is capped lower by [ScriptCaps.MAX_SOURCE_BYTES]. */
    const val MAX_IMPORT_BYTES = 1024 * 1024
    private const val BUFFER = 8 * 1024
    private const val BOM = '﻿'

    sealed interface Read {
        data class Bytes(val bytes: ByteArray) : Read
        data object TooLarge : Read
    }

    /** Reads at most [cap] bytes; one more byte than that is [Read.TooLarge], and the rest of the stream is never read. */
    fun readCapped(input: InputStream, cap: Int = MAX_IMPORT_BYTES): Read {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > cap) return Read.TooLarge
            out.write(buf, 0, n)
        }
        return Read.Bytes(out.toByteArray())
    }

    /** Strict UTF-8 (a malformed byte is not repaired) without a leading byte order mark; null when the bytes are not UTF-8 text. */
    fun decode(bytes: ByteArray): String? {
        val text = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { return null }
        return text.removePrefix(BOM.toString())
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes of [source]: what is stored, shown and compared. */
    fun sha256(source: String): String = sha256(source.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
