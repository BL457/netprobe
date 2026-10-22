package com.northwalessolutions.netprobe.oui

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A compact, immutable vendor table: three sorted prefix arrays (36, 28 and 24 bits) pointing into one vendor string table.
 * Lookup takes the longest prefix first. The file form is: magic, vendor count, the vendor strings, then for each prefix size
 * its entry count followed by (prefix, vendor index) pairs in ascending prefix order.
 */
class OuiTable private constructor(private val vendors: Array<String>, private val prefixes: Array<LongArray>, private val indexes: Array<IntArray>) {
    val size: Int get() = prefixes.sumOf { it.size }

    /** The vendor of a 48-bit address, longest prefix first, or null. */
    fun lookup(mac: Long): String? {
        for (slot in BITS.indices) {
            val key = mac ushr (MAC_BITS - BITS[slot])
            val at = prefixes[slot].binarySearch(key)
            if (at >= 0) return vendors[indexes[slot][at]]
        }
        return null
    }

    fun write(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC)
        d.writeInt(vendors.size)
        for (v in vendors) d.writeUTF(v)
        for (slot in BITS.indices) {
            d.writeInt(prefixes[slot].size)
            for (i in prefixes[slot].indices) { d.writeLong(prefixes[slot][i]); d.writeInt(indexes[slot][i]) }
        }
        d.flush()
    }

    class Builder {
        private val names = LinkedHashMap<String, Int>()
        private val entries = Array(BITS.size) { LinkedHashMap<Long, Int>() }   // a later entry for the same prefix replaces the earlier

        fun add(bits: Int, prefix: Long, vendor: String) {
            val slot = BITS.indexOf(bits)
            require(slot >= 0) { "unsupported prefix size $bits" }
            entries[slot][prefix] = names.getOrPut(vendor) { names.size }
        }

        fun build(): OuiTable {
            val prefixes = Array(BITS.size) { LongArray(0) }
            val indexes = Array(BITS.size) { IntArray(0) }
            for (slot in BITS.indices) {
                val sorted = entries[slot].entries.sortedBy { it.key }
                prefixes[slot] = LongArray(sorted.size) { sorted[it].key }
                indexes[slot] = IntArray(sorted.size) { sorted[it].value }
            }
            return OuiTable(names.keys.toTypedArray(), prefixes, indexes)
        }
    }

    companion object {
        /** Longest first. */
        val BITS = intArrayOf(36, 28, 24)
        private const val MAC_BITS = 48
        private const val MAGIC = 0x4F554931   // "OUI1"
        private const val MAX_COUNT = 5_000_000

        /** Reads a table written by [write]; throws IOException on a file that is not one. */
        fun read(input: InputStream): OuiTable {
            val d = DataInputStream(input.buffered())
            if (d.readInt() != MAGIC) throw IOException("not a vendor table")
            val vendorCount = d.readInt()
            if (vendorCount !in 0..MAX_COUNT) throw IOException("bad vendor count")
            val vendors = Array(vendorCount) { d.readUTF() }
            val prefixes = Array(BITS.size) { LongArray(0) }
            val indexes = Array(BITS.size) { IntArray(0) }
            for (slot in BITS.indices) {
                val n = d.readInt()
                if (n !in 0..MAX_COUNT) throw IOException("bad entry count")
                val p = LongArray(n); val x = IntArray(n)
                for (i in 0 until n) {
                    p[i] = d.readLong(); x[i] = d.readInt()
                    if (x[i] !in vendors.indices || (i > 0 && p[i] <= p[i - 1])) throw IOException("bad entry")
                }
                prefixes[slot] = p; indexes[slot] = x
            }
            if (d.read() != -1) throw IOException("trailing bytes")
            return OuiTable(vendors, prefixes, indexes)
        }
    }
}
