package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.tools.smb.Smb2
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Code parsers behind the probe `parser` field (Phase 3E spec 4): a binary reply in, one text line out, which the probe's regex
 * matches then read, so every match stays a regex over text. A parser returns null for a reply that is not its protocol.
 */
object ProbeParsers {
    const val SMB2 = "smb2-negotiate"
    const val MONGODB = "mongodb-ismaster"
    const val MODBUS = "modbus-device-id"
    const val RDP = "rdp-x224"
    val NAMES: Set<String> = setOf(SMB2, MONGODB, MODBUS, RDP)

    fun parse(name: String, reply: ByteArray): String? = when (name) {
        SMB2 -> smb2(reply)
        MONGODB -> mongodb(reply)
        MODBUS -> modbus(reply)
        RDP -> rdp(reply)
        else -> null
    }

    // ---- SMB2 NEGOTIATE response (the 3B parser, behind the 4-byte NetBIOS session header) ----
    private const val NETBIOS_HEADER = 4
    private const val CAPABILITIES_OFFSET = Smb2.HEADER_SIZE + 8 + 16   // after StructureSize, SecurityMode, Dialect, Reserved, ServerGuid

    fun smb2(reply: ByteArray): String? {
        val msg = if (reply.size > NETBIOS_HEADER && reply[0].toInt() == 0) reply.copyOfRange(NETBIOS_HEADER, reply.size) else reply
        if (Smb2.isSmb1(msg)) return "SMB1"
        val r = Smb2.parseNegotiate(msg) ?: return null
        if (r.status != Smb2.STATUS_SUCCESS) return "SMB2 refused status 0x%08x".format(r.status)
        val caps = if (msg.size >= CAPABILITIES_OFFSET + 4) ByteBuffer.wrap(msg).order(ByteOrder.LITTLE_ENDIAN).getInt(CAPABILITIES_OFFSET) else 0
        return "SMB2 dialect 0x%04x signing %s capabilities 0x%x".format(r.dialect, if (r.signingRequired) "required" else "enabled", caps)
    }

    // ---- RDP X.224 Connection Confirm ----
    private const val TPKT_VERSION = 0x03
    private const val X224_CODE_INDEX = 5
    private const val X224_CONNECTION_CONFIRM = 0xD0
    private const val BYTE_MASK = 0xFF

    fun rdp(reply: ByteArray): String? {
        if (reply.size <= X224_CODE_INDEX) return null
        if ((reply[0].toInt() and BYTE_MASK) != TPKT_VERSION || reply[1].toInt() != 0) return null
        return if ((reply[X224_CODE_INDEX].toInt() and BYTE_MASK) == X224_CONNECTION_CONFIRM) "rdp confirm" else null
    }

    // ---- MongoDB OP_MSG reply: MsgHeader (16), flagBits (4), section kind 0, one BSON document ----
    private const val OP_MSG = 2013
    private const val MSG_HEADER = 16
    private const val SECTION_OFFSET = MSG_HEADER + 4

    fun mongodb(reply: ByteArray): String? {
        if (reply.size < SECTION_OFFSET + 1 + 5) return null
        val b = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(12) != OP_MSG || reply[SECTION_OFFSET].toInt() != 0) return null
        val doc = Bson.topLevel(reply, SECTION_OFFSET + 1) ?: return null
        val wire = doc["maxWireVersion"] as? Int ?: return null
        val version = (doc["version"] as? String)?.filter { it.isLetterOrDigit() || it == '.' || it == '-' }?.take(40)
        return "mongodb" + (if (version.isNullOrEmpty()) "" else " version=$version") + " maxWireVersion=$wire"
    }

    /** A minimal bounds-checked BSON reader: top-level int32, int64 (as Long), string and bool values by name; other types are skipped; anything odd ends the read. */
    object Bson {
        fun topLevel(buf: ByteArray, start: Int): Map<String, Any>? {
            if (start + 5 > buf.size) return null
            val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val len = b.getInt(start)
            val end = start + len
            if (len < 5 || end > buf.size) return null
            val out = HashMap<String, Any>(); var p = start + 4
            while (p < end - 1) {
                val type = buf[p].toInt() and BYTE_MASK; p++
                val nameEnd = (p until end).firstOrNull { buf[it].toInt() == 0 } ?: return out
                val name = String(buf, p, nameEnd - p, Charsets.ISO_8859_1); p = nameEnd + 1
                when (type) {
                    0x01, 0x09, 0x11, 0x12 -> { if (p + 8 > end) return out; if (type == 0x12) out[name] = b.getLong(p); p += 8 }
                    0x02 -> {
                        if (p + 4 > end) return out
                        val n = b.getInt(p); if (n < 1 || p + 4 + n > end) return out
                        out[name] = String(buf, p + 4, n - 1, Charsets.UTF_8); p += 4 + n
                    }
                    0x03, 0x04 -> { if (p + 4 > end) return out; val n = b.getInt(p); if (n < 5 || p + n > end) return out; p += n }
                    0x05 -> { if (p + 5 > end) return out; val n = b.getInt(p); if (n < 0 || p + 5 + n > end) return out; p += 5 + n }
                    0x07 -> { if (p + 12 > end) return out; p += 12 }
                    0x08 -> { if (p + 1 > end) return out; out[name] = buf[p].toInt() != 0; p += 1 }
                    0x0A -> {}
                    0x10 -> { if (p + 4 > end) return out; out[name] = b.getInt(p); p += 4 }
                    0x13 -> { if (p + 16 > end) return out; p += 16 }
                    else -> return out
                }
            }
            return out
        }
    }

    // ---- Modbus/TCP: MBAP (7), function 0x2B, MEI 0x0E, read device id objects 0..2 ----
    private const val MBAP = 7
    private const val FC_MEI = 0x2B
    private const val FC_MEI_EXCEPTION = 0xAB
    private const val MEI_READ_DEVICE_ID = 0x0E
    private const val OBJECTS_START = MBAP + 7   // FC, MEI, code, conformity, more, next, count

    fun modbus(reply: ByteArray): String? {
        if (reply.size < MBAP + 2) return null
        if (reply[2].toInt() != 0 || reply[3].toInt() != 0) return null   // protocol id is always 0
        val fc = reply[MBAP].toInt() and BYTE_MASK
        if (fc == FC_MEI_EXCEPTION) return "modbus exception ${reply.getOrNull(MBAP + 1)?.toInt()?.and(BYTE_MASK) ?: 0}"
        if (fc != FC_MEI || (reply[MBAP + 1].toInt() and BYTE_MASK) != MEI_READ_DEVICE_ID || reply.size < OBJECTS_START) return null
        var count = reply[OBJECTS_START - 1].toInt() and BYTE_MASK
        var p = OBJECTS_START
        val names = listOf("vendor", "product", "revision")
        val found = LinkedHashMap<String, String>()
        while (count > 0 && p + 2 <= reply.size) {
            val id = reply[p].toInt() and BYTE_MASK; val n = reply[p + 1].toInt() and BYTE_MASK
            if (p + 2 + n > reply.size) break
            if (id in names.indices) {
                val v = String(reply, p + 2, n, Charsets.ISO_8859_1).filter { it.code in 32..126 }.trim().take(60)
                if (v.isNotEmpty()) found[names[id]] = v
            }
            p += 2 + n; count--
        }
        return "modbus" + found.entries.joinToString("") { " ${it.key}=${it.value}" }
    }
}
