package com.northwalessolutions.netprobe.oui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException

/** When the table was last written and how many entries it holds; kept in the preferences. */
data class OuiInfo(val updatedAt: Long, val count: Int)

interface OuiMeta {
    fun read(): OuiInfo?
    fun write(info: OuiInfo)
}

/**
 * The offline vendor table the Director downloads from Settings (Director ruling 2026-10-22 b). Nothing is built in: with no file,
 * [lookup] names no vendor. The table is read from [file] once, on the first lookup, and replaced atomically by [replace].
 */
class OuiStore(private val file: File, private val meta: OuiMeta, private val log: (Throwable) -> Unit = {}) {
    private val lock = Any()
    @Volatile private var table: OuiTable? = null
    @Volatile private var loaded = false
    private val _info = MutableStateFlow(meta.read())
    val info: StateFlow<OuiInfo?> = _info

    /**
     * The vendor of [mac] ("aa:bb:cc:dd:ee:ff", "-" or no separators). A multicast address and an unparsable one give null; a locally
     * administered (randomised) address gives [OuiCopy.PRIVATE_ADDRESS] and is never looked up.
     */
    fun lookup(mac: String): String? {
        val value = parse(mac) ?: return null
        val first = (value ushr FIRST_OCTET_SHIFT).toInt() and OCTET_MASK
        if (first and MULTICAST_BIT != 0) return null
        if (first and LOCAL_BIT != 0) return OuiCopy.PRIVATE_ADDRESS
        return load()?.lookup(value)
    }

    private fun load(): OuiTable? {
        if (loaded) return table
        synchronized(lock) {
            if (!loaded) {
                table = try { if (file.isFile) file.inputStream().use(OuiTable::read) else null } catch (e: IOException) { log(e); null }
                loaded = true
            }
            return table
        }
    }

    /** Writes [next] to a temporary file beside the table and renames it over the old one, so a failure leaves the old table whole. */
    fun replace(next: OuiTable, now: Long) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
            try {
                temp.outputStream().use { next.write(it) }
                if (!temp.renameTo(file)) {
                    if (file.exists() && !file.delete()) throw IOException("cannot replace ${file.name}")
                    if (!temp.renameTo(file)) throw IOException("cannot rename ${temp.name}")
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
            table = next; loaded = true
            val info = OuiInfo(now, next.size)
            meta.write(info); _info.value = info
        }
    }

    companion object {
        const val FILE_NAME = "oui.bin"
        private const val TEMP_SUFFIX = ".tmp"
        private const val HEX_DIGITS = 12
        private const val FIRST_OCTET_SHIFT = 40
        private const val OCTET_MASK = 0xFF
        private const val MULTICAST_BIT = 0x01
        private const val LOCAL_BIT = 0x02
        private const val HEX = 16

        /** A 48-bit value from twelve hex digits with optional ":", "-" or "." separators; null for anything else. */
        fun parse(mac: String): Long? {
            val digits = mac.filter { it != ':' && it != '-' && it != '.' }
            if (digits.length != HEX_DIGITS || !digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            return digits.toLong(HEX)
        }
    }
}
