package com.northwalessolutions.netprobe.engine.discovery

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
        return try {
            runInterruptible(Dispatchers.IO) {
                DatagramSocket().use { s ->
                    s.soTimeout = timeoutMs
                    val req = buildGet(); s.send(DatagramPacket(req, req.size, ip, 161))
                    val buf = ByteArray(4096); val p = DatagramPacket(buf, buf.size); s.receive(p)
                    parseResponse(buf.copyOf(p.length))
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
}
