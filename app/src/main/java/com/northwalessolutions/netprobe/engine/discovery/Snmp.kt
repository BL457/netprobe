package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.bindOrFail
import com.northwalessolutions.netprobe.engine.common.currentBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address

data class SnmpInfo(val sysDescr: String?, val sysName: String?, val sysObjectId: String?)

internal object Ber {
    fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }
    fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + len(content.size) + content
    fun int(v: Int): ByteArray { // minimal two's complement
        var bytes = ByteArray(4) { ((v shr (8 * (3 - it))) and 0xff).toByte() }
        while (bytes.size > 1 && ((bytes[0].toInt() == 0 && bytes[1].toInt() and 0x80 == 0) || (bytes[0].toInt() == -1 && bytes[1].toInt() and 0x80 != 0))) bytes = bytes.copyOfRange(1, bytes.size)
        return tlv(0x02, bytes)
    }
    fun str(s: String) = tlv(0x04, s.toByteArray(Charsets.UTF_8))
    fun nul() = byteArrayOf(0x05, 0x00)
    fun oid(parts: IntArray): ByteArray {
        val out = ByteArrayOutputStream(); out.write(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) { var v = parts[i]; val stack = ArrayList<Int>(); do { stack.add(v and 0x7f); v = v ushr 7 } while (v > 0)
            for (k in stack.indices.reversed()) out.write(stack[k] or (if (k > 0) 0x80 else 0)) }
        return tlv(0x06, out.toByteArray())
    }
    fun seq(vararg items: ByteArray) = tlv(0x30, items.fold(ByteArray(0)) { a, b -> a + b })

    class Reader(private val b: ByteArray, var pos: Int = 0) {
        fun tag(): Int = b[pos++].toInt() and 0xff
        fun length(): Int { val f = b[pos++].toInt() and 0xff; if (f < 0x80) return f
            val n = f and 0x7f; require(n in 1..3) ; var v = 0; repeat(n) { v = (v shl 8) or (b[pos++].toInt() and 0xff) }; return v }
        fun bytes(n: Int): ByteArray { require(pos + n <= b.size); val r = b.copyOfRange(pos, pos + n); pos += n; return r }
        /** Counter32, Gauge32, TimeTicks: unsigned 32-bit into Long (spec 6.4). The tag has been read; reads the length and content. */
        fun unsigned(): Long { val n = length(); var v = 0L; for (x in bytes(n)) v = (v shl 8) or (x.toLong() and 0xff); return v }
        /** INTEGER content as a sign-extended Long. The tag has been read. */
        fun signed(): Long { val b = bytes(length()); var v = if (b.isNotEmpty() && b[0] < 0) -1L else 0L; for (x in b) v = (v shl 8) or (x.toLong() and 0xff); return v }
        fun int(): Int { require(tag() == 0x02); val n = length(); var v = 0; for (x in bytes(n)) v = (v shl 8) or (x.toInt() and 0xff); return v }
        fun oidString(content: ByteArray): String { val parts = ArrayList<Int>(); parts += (content[0].toInt() and 0xff) / 40; parts += (content[0].toInt() and 0xff) % 40
            var v = 0; for (i in 1 until content.size) { val c = content[i].toInt() and 0xff; v = (v shl 7) or (c and 0x7f); if (c and 0x80 == 0) { parts += v; v = 0 } }
            return parts.joinToString(".") }
    }
}

object Snmp {
    private val OID_DESCR = intArrayOf(1, 3, 6, 1, 2, 1, 1, 1, 0)
    private val OID_OBJID = intArrayOf(1, 3, 6, 1, 2, 1, 1, 2, 0)
    private val OID_NAME = intArrayOf(1, 3, 6, 1, 2, 1, 1, 5, 0)

    fun buildGet(community: String = "public", requestId: Int = 0x5a5a): ByteArray {
        val varbinds = Ber.seq(Ber.seq(Ber.oid(OID_DESCR), Ber.nul()), Ber.seq(Ber.oid(OID_NAME), Ber.nul()), Ber.seq(Ber.oid(OID_OBJID), Ber.nul()))
        val pdu = Ber.tlv(0xa0, Ber.int(requestId) + Ber.int(0) + Ber.int(0) + varbinds)
        return Ber.seq(Ber.int(1), Ber.str(community), pdu)
    }

    fun parseResponse(bytes: ByteArray): SnmpInfo? {
        return try {
            val r = Ber.Reader(bytes)
            require(r.tag() == 0x30); val total = r.length(); require(total <= bytes.size - r.pos)
            r.int()                                   // version
            require(r.tag() == 0x04); r.bytes(r.length())   // community
            require(r.tag() == 0xa2); r.length()
            r.int()                                   // request id
            val errorStatus = r.int(); r.int()        // error status, error index
            if (errorStatus != 0) return null
            require(r.tag() == 0x30); val vbEnd = r.pos + r.length().also { require(it <= bytes.size - r.pos) }
            var descr: String? = null; var name: String? = null; var objId: String? = null
            while (r.pos < vbEnd) {
                require(r.tag() == 0x30); r.length()
                require(r.tag() == 0x06); val oid = r.oidString(r.bytes(r.length()))
                val t = r.tag(); val n = r.length(); val content = r.bytes(n)
                val value = when (t) { 0x04 -> String(content, Charsets.UTF_8); 0x06 -> r.oidString(content); else -> null }
                when (oid) { "1.3.6.1.2.1.1.1.0" -> descr = value; "1.3.6.1.2.1.1.5.0" -> name = value; "1.3.6.1.2.1.1.2.0" -> objId = value }
            }
            if (descr == null && name == null && objId == null) null else SnmpInfo(descr, name, objId)
        } catch (_: Exception) { null }
    }

    suspend fun query(ip: Inet4Address, timeoutMs: Int = 1000): SnmpInfo? {
        val binder = currentBinder()
        return try {
            runInterruptible(Dispatchers.IO) {
                DatagramSocket().use { s ->
                    binder?.bindOrFail(s)
                    s.soTimeout = timeoutMs
                    val req = buildGet(); s.send(DatagramPacket(req, req.size, ip, 161))
                    val buf = ByteArray(4096); val p = DatagramPacket(buf, buf.size); s.receive(p)
                    parseResponse(buf.copyOf(p.length))
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: BindFailedException) { throw e } catch (_: Exception) { null }
    }
}

/** One varbind as the walk reads it: [str] for strings, OIDs and IpAddress; [long] for numbers; [raw] keeps OCTET STRING bytes (MACs). */
data class SnmpVarbind(val oid: String, val type: Int, val str: String?, val long: Long?, val raw: ByteArray?, val endOfMib: Boolean, val exception: Boolean)
data class SnmpResponse(val requestId: Int, val errorStatus: Int, val varbinds: List<SnmpVarbind>)

/**
 * SNMPv2c GET (0xa0) and GETNEXT (0xa1) requests and their responses (0xa2), with the application types and exceptions (spec 6.4).
 * A reply is hostile input: every length is checked against the bytes left, the structure is a fixed two levels deep (no recursion),
 * an OID is at most [MAX_OID_ARCS] arcs of at most an Int each, numbers are at most [MAX_NUMBER_BYTES] bytes and a reply carries at
 * most [MAX_VARBINDS] varbinds. Text is not cleaned here ([SnmpVarbind.raw] must keep the bytes); the walk cleans it before a finding.
 */
object SnmpPdu {
    const val GET = 0xa0; const val GETNEXT = 0xa1; const val RESPONSE = 0xa2
    private const val T_INTEGER = 0x02; private const val T_OCTETS = 0x04; private const val T_NULL = 0x05; private const val T_OID = 0x06
    private const val T_IPADDRESS = 0x40; private const val T_COUNTER32 = 0x41; private const val T_GAUGE32 = 0x42; private const val T_TIMETICKS = 0x43
    private const val NO_SUCH_OBJECT = 0x80; private const val NO_SUCH_INSTANCE = 0x81; private const val END_OF_MIB_VIEW = 0x82
    private const val SEQUENCE = 0x30; private const val VERSION_2C = 1
    /** RFC 2578 3.5: an OID has at most 128 sub-identifiers. */
    const val MAX_OID_ARCS = 128
    const val MAX_VARBINDS = 64
    private const val MAX_NUMBER_BYTES = 9            // Counter64 is 8 content bytes plus a leading zero
    private const val MAX_ARC_BYTES = 5               // 35 bits of base-128 digits; the value is then checked against Int.MAX_VALUE
    private const val IPV4_BYTES = 4

    fun oidParts(oid: String): IntArray = oid.split('.').map { it.toInt() }.toIntArray()
    fun build(pduType: Int, oids: List<String>, community: String, requestId: Int): ByteArray {
        val varbinds = Ber.seq(*oids.map { Ber.seq(Ber.oid(oidParts(it)), Ber.nul()) }.toTypedArray())
        return Ber.seq(Ber.int(VERSION_2C), Ber.str(community), Ber.tlv(pduType, Ber.int(requestId) + Ber.int(0) + Ber.int(0) + varbinds))
    }

    /** Null on anything malformed: nothing a device sends can throw out of here (spec 11.2). */
    fun parse(bytes: ByteArray): SnmpResponse? {
        return try {
            val r = Ber.Reader(bytes)
            fun fit(n: Int): Int { require(n >= 0 && n <= bytes.size - r.pos); return n }
            require(r.tag() == SEQUENCE); fit(r.length())
            r.int()                                                        // version
            require(r.tag() == T_OCTETS); r.bytes(fit(r.length()))         // community
            require(r.tag() == RESPONSE); fit(r.length())
            val id = r.int(); val errorStatus = r.int(); r.int()
            require(r.tag() == SEQUENCE); val end = fit(r.length()).let { r.pos + it }
            val out = ArrayList<SnmpVarbind>()
            while (r.pos < end) {
                require(out.size < MAX_VARBINDS)
                require(r.tag() == SEQUENCE); val vbEnd = fit(r.length()).let { r.pos + it }; require(vbEnd <= end)
                require(r.tag() == T_OID); val oid = oidOf(r.bytes(fit(r.length())), r)
                val t = r.tag(); val lengthAt = r.pos; val n = fit(r.length())
                require(r.pos + n == vbEnd)                                   // the value fills the rest of its varbind exactly
                r.pos = lengthAt                                              // back to the length: the typed readers read it themselves
                out += when (t) {
                    T_OCTETS -> { val b = r.bytes(r.length()); SnmpVarbind(oid, t, String(b, Charsets.UTF_8), null, b, false, false) }
                    T_OID -> SnmpVarbind(oid, t, oidOf(r.bytes(r.length()), r), null, null, false, false)
                    T_IPADDRESS -> { val b = r.bytes(r.length()); require(b.size == IPV4_BYTES)
                        SnmpVarbind(oid, t, b.joinToString(".") { (it.toInt() and 0xff).toString() }, null, null, false, false) }
                    T_COUNTER32, T_GAUGE32, T_TIMETICKS -> { require(n in 1..MAX_NUMBER_BYTES); SnmpVarbind(oid, t, null, r.unsigned(), null, false, false) }
                    T_INTEGER -> { require(n in 1..MAX_NUMBER_BYTES); SnmpVarbind(oid, t, null, r.signed(), null, false, false) }
                    NO_SUCH_OBJECT, NO_SUCH_INSTANCE, END_OF_MIB_VIEW -> { r.bytes(r.length()); SnmpVarbind(oid, t, null, null, null, t == END_OF_MIB_VIEW, true) }
                    else -> { r.bytes(r.length()); SnmpVarbind(oid, t, null, null, null, false, t == T_NULL) }
                }
            }
            require(r.pos == end)
            SnmpResponse(id, errorStatus, out)
        } catch (_: Exception) { null }
    }

    /** Validates OID content (non-empty, single-byte first arc pair, every arc terminated, at most an Int, bounded arc count) before decoding. */
    private fun oidOf(content: ByteArray, r: Ber.Reader): String {
        require(content.isNotEmpty() && content[0] >= 0 && content.last() >= 0)
        var arcs = 2; var width = 0; var value = 0L
        for (i in 1 until content.size) {
            val c = content[i].toInt() and 0xff
            width++; value = (value shl 7) or (c and 0x7f).toLong()
            require(width <= MAX_ARC_BYTES && value <= Int.MAX_VALUE)
            if (c and 0x80 == 0) { arcs++; width = 0; value = 0 }
        }
        require(arcs <= MAX_OID_ARCS)
        return r.oidString(content)
    }
}
