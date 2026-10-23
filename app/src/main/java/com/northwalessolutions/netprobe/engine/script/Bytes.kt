package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Byte helpers behind ctx.bytes. A byte string is an array of numbers 0 to 255 in the script and a ByteArray here. */
internal object Bytes {
    private const val BYTE_MASK = 0xff
    private const val MAX_BYTE = 255

    /** A string (UTF-8) or an array of byte numbers, as bytes; anything else is an error naming the option. */
    fun fromValue(v: Any?, what: String, key: String): ByteArray = when (v) {
        is String -> v.toByteArray(Charsets.UTF_8).also { capCheck(it.size, what, key) }
        is List<*> -> {
            capCheck(v.size, what, key)
            ByteArray(v.size) { i ->
                val n = v[i] as? Double ?: throw ScriptHostError(ScriptCopy.notABytes(what, key))
                if (n < 0 || n > MAX_BYTE || n != Math.floor(n)) throw ScriptHostError(ScriptCopy.notABytes(what, key))
                n.toInt().toByte()
            }
        }
        else -> throw ScriptHostError(ScriptCopy.notABytes(what, key))
    }

    private fun capCheck(size: Int, what: String, key: String) {
        if (size > ScriptCaps.MAX_BODY_BYTES) throw ScriptHostError(ScriptCopy.tooManyBytes(what, key))
    }

    fun fromHex(text: String): ByteArray {
        val clean = text.filter { !it.isWhitespace() && it != ':' }
        if (clean.length % 2 != 0 || clean.any { Character.digit(it, 16) < 0 }) throw ScriptHostError(ScriptCopy.BAD_HEX)
        if (clean.length / 2 > ScriptCaps.MAX_ARRAY_LENGTH) throw ScriptHostError(ScriptCopy.VALUE_TOO_LARGE)
        return ByteArray(clean.length / 2) { ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte() }
    }

    fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) { sb.append(HEX[(x.toInt() shr 4) and 0xf]); sb.append(HEX[x.toInt() and 0xf]) }
        return sb.toString()
    }

    /** Standard base64 with padding, the form HTTP Basic authentication uses. */
    fun toBase64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)

    /** Standard base64, or the URL-safe alphabet; white space and missing padding are accepted, anything else is [ScriptCopy.BAD_BASE64]. */
    fun fromBase64(text: String): ByteArray {
        val clean = text.filter { !it.isWhitespace() }.trimEnd('=').replace('-', '+').replace('_', '/')
        if (clean.length % BASE64_GROUP == 1 || clean.any { it !in BASE64_ALPHABET }) throw ScriptHostError(ScriptCopy.BAD_BASE64)
        val out = try { java.util.Base64.getDecoder().decode(clean) } catch (_: IllegalArgumentException) { throw ScriptHostError(ScriptCopy.BAD_BASE64) }
        if (out.size > ScriptCaps.MAX_ARRAY_LENGTH) throw ScriptHostError(ScriptCopy.VALUE_TOO_LARGE)
        return out
    }

    /** Lower-case hex of the MD5, SHA-1 or SHA-256 digest of [b] ([algorithm] is the JCA name). */
    fun digestHex(algorithm: String, b: ByteArray): String = toHex(java.security.MessageDigest.getInstance(algorithm).digest(b))

    /** Lossy UTF-8 decode: bad sequences become U+FFFD, so a binary banner never throws. */
    fun toUtf8(b: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        .decode(ByteBuffer.wrap(b)).toString()

    fun uint(value: Double, bytes: Int, order: ByteOrder): ByteArray {
        val max = (1L shl (bytes * 8)) - 1
        if (value.isNaN() || value < 0 || value > max || value != Math.floor(value)) throw ScriptHostError(ScriptCopy.outOfRangeNumber(max))
        val n = value.toLong()
        val out = ByteArray(bytes)
        for (i in 0 until bytes) {
            val shift = if (order == ByteOrder.BIG_ENDIAN) (bytes - 1 - i) * 8 else i * 8
            out[i] = ((n shr shift) and BYTE_MASK.toLong()).toByte()
        }
        return out
    }

    fun readUint(b: ByteArray, offset: Int, bytes: Int, order: ByteOrder): Double {
        if (offset < 0 || offset + bytes > b.size) throw ScriptHostError(ScriptCopy.READ_PAST_END)
        var n = 0L
        for (i in 0 until bytes) {
            val x = (b[offset + i].toLong() and BYTE_MASK.toLong())
            n = if (order == ByteOrder.BIG_ENDIAN) (n shl 8) or x else n or (x shl (8 * i))
        }
        return n.toDouble()
    }

    private val HEX = "0123456789abcdef".toCharArray()
    private const val BASE64_GROUP = 4
    private val BASE64_ALPHABET = (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('+', '/')).toSet()
}
