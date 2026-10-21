package com.northwalessolutions.netprobe.engine.discovery

/**
 * DNS wire helpers shared by DeviceInfoTxt (moved from it, behaviour unchanged) and the listener's MdnsPacket (3C spec 3.3). Every read is
 * bounds-checked and returns null rather than throw; compressed names follow at most MAX_POINTER_JUMPS pointers.
 */
internal object DnsWire {
    private const val POINTER_MASK = 0xC0
    private const val OFFSET_MASK = 0x3F
    private const val MAX_POINTER_JUMPS = 8
    private const val BYTE_MASK = 0xff
    private const val BITS_PER_BYTE = 8
    /** DeviceInfoTxt's behaviour: no limit on the number of labels. */
    const val NO_LABEL_LIMIT = Int.MAX_VALUE

    fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (b[at + 1].toInt() and BYTE_MASK)

    /** Reads a possibly compressed name. Returns the dotted name and the position after it in the original stream, or null. */
    fun readName(b: ByteArray, start: Int, maxLabels: Int = NO_LABEL_LIMIT): Pair<String, Int>? {
        val parts = ArrayList<String>(); var pos = start; var end = -1; var jumps = 0
        while (true) {
            if (pos < 0 || pos >= b.size) return null
            val len = b[pos].toInt() and BYTE_MASK
            when {
                len == 0 -> { if (end < 0) end = pos + 1; return parts.joinToString(".") to end }
                (len and POINTER_MASK) == POINTER_MASK -> {
                    if (pos + 1 >= b.size || ++jumps > MAX_POINTER_JUMPS) return null
                    if (end < 0) end = pos + 2
                    pos = ((len and OFFSET_MASK) shl BITS_PER_BYTE) or (b[pos + 1].toInt() and BYTE_MASK)
                }
                (len and POINTER_MASK) != 0 -> return null
                else -> {
                    if (pos + 1 + len > b.size || parts.size >= maxLabels) return null
                    parts += String(b, pos + 1, len, Charsets.UTF_8); pos += 1 + len
                }
            }
        }
    }

    /** TXT rdata as key to value: keys lower-cased, the first value of a key kept, entries without '=' skipped. */
    fun parseTxtRdata(b: ByteArray, start: Int, end: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>(); var p = start
        while (p < end) {
            val l = b[p].toInt() and BYTE_MASK
            if (l == 0 || p + 1 + l > end) break
            val s = String(b, p + 1, l, Charsets.UTF_8)
            val eq = s.indexOf('=')
            if (eq > 0) out.putIfAbsent(s.substring(0, eq).lowercase(), s.substring(eq + 1))
            p += 1 + l
        }
        return out
    }
}
