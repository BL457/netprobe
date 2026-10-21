package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.tools.smb.Smb2
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The binary-to-text parsers behind the probe `parser` field. */
class ProbeCodeParsersTest {
    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    // ---- SMB2 ----
    /** A NEGOTIATE response behind a NetBIOS header: dialect, security mode, capabilities. */
    private fun smb2Reply(dialect: Int, signingRequired: Boolean, caps: Int, status: Int = 0): ByteArray {
        val h = Smb2.header(Smb2.NEGOTIATE, 0, 0, 0)
        ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).putInt(8, status)
        val body = le(64).putShort(65).putShort(if (signingRequired) 3 else 1).putShort(dialect.toShort()).putShort(0).put(ByteArray(16)).putInt(caps).array()
        val msg = h + body
        return byteArrayOf(0, 0, (msg.size shr 8).toByte(), msg.size.toByte()) + msg
    }
    @Test fun `smb2 dialect and capabilities become a text line`() {
        assertEquals("SMB2 dialect 0x0311 signing required capabilities 0x7f", ProbeParsers.smb2(smb2Reply(0x0311, true, 0x7F)))
        assertEquals("SMB2 dialect 0x0210 signing enabled capabilities 0x0", ProbeParsers.smb2(smb2Reply(0x0210, false, 0)))
    }
    @Test fun `smb2 without the netbios header and an error status and an smb1 reply`() {
        assertEquals("SMB2 dialect 0x0302 signing enabled capabilities 0x1", ProbeParsers.smb2(smb2Reply(0x0302, false, 1).let { it.copyOfRange(4, it.size) }))
        assertEquals("SMB2 refused status 0xc00000bb", ProbeParsers.smb2(smb2Reply(0, false, 0, status = 0xC00000BB.toInt())))
        assertEquals("SMB1", ProbeParsers.smb2(byteArrayOf(0, 0, 0, 8, 0xFF.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte(), 0, 0, 0, 0)))
        assertNull(ProbeParsers.smb2("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()))
        assertNull(ProbeParsers.smb2(ByteArray(0)))
    }

    // ---- RDP ----
    @Test fun `rdp confirm and three wrong replies`() {
        val confirm = byteArrayOf(0x03, 0x00, 0x00, 0x13, 0x0E, 0xD0.toByte(), 0x00, 0x00, 0x12, 0x34, 0x00, 0x02, 0x1F, 0x08, 0x00, 0x08, 0x00, 0x00, 0x00)
        assertEquals("rdp confirm", ProbeParsers.rdp(confirm))
        assertNull(ProbeParsers.rdp(confirm.copyOf().also { it[0] = 0x15 })); assertNull(ProbeParsers.rdp(confirm.copyOf().also { it[5] = 0xE0.toByte() }))
        assertNull(ProbeParsers.rdp(confirm.copyOf(5)))
    }

    // ---- MongoDB ----
    private fun bsonInt(name: String, v: Int) = byteArrayOf(0x10) + name.toByteArray() + 0 + le(4).putInt(v).array()
    private fun bsonStr(name: String, v: String) = byteArrayOf(0x02) + name.toByteArray() + 0 + le(4).putInt(v.length + 1).array() + v.toByteArray() + 0
    private fun bsonBool(name: String, v: Boolean) = byteArrayOf(0x08) + name.toByteArray() + 0 + byteArrayOf(if (v) 1 else 0)
    private fun bsonDoc(vararg elements: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().also { o -> elements.forEach { o.write(it) } }.toByteArray()
        return le(4).putInt(4 + body.size + 1).array() + body + 0
    }
    private fun opMsg(doc: ByteArray): ByteArray {
        val len = 16 + 4 + 1 + doc.size
        return le(16).putInt(len).putInt(7).putInt(1).putInt(2013).array() + le(4).putInt(0).array() + byteArrayOf(0) + doc
    }
    @Test fun `mongodb ismaster reply gives wire version and version when present`() {
        val doc = bsonDoc(bsonBool("ismaster", true), bsonInt("maxBsonObjectSize", 16777216), bsonInt("maxWireVersion", 21), byteArrayOf(0x0A) + "x".toByteArray() + 0)
        assertEquals("mongodb maxWireVersion=21", ProbeParsers.mongodb(opMsg(doc)))
        assertEquals("mongodb version=7.0.5 maxWireVersion=21", ProbeParsers.mongodb(opMsg(bsonDoc(bsonStr("version", "7.0.5"), bsonInt("maxWireVersion", 21)))))
    }
    @Test fun `mongodb parser survives truncation and junk`() {
        val good = opMsg(bsonDoc(bsonStr("version", "7.0.5"), bsonInt("maxWireVersion", 21)))
        for (cut in 0 until good.size) ProbeParsers.mongodb(good.copyOf(cut))   // must not throw
        assertNull(ProbeParsers.mongodb(ByteArray(64) { 0xFF.toByte() }))
        assertNull(ProbeParsers.mongodb(opMsg(bsonDoc(bsonInt("other", 1)))))      // no maxWireVersion
        assertNull(ProbeParsers.mongodb("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()))
        // a string length that points past the document ends the read without an exception
        val lying = opMsg(bsonDoc(byteArrayOf(0x02) + "version".toByteArray() + 0 + le(4).putInt(9999).array()))
        assertNull(ProbeParsers.mongodb(lying))
    }

    // ---- Modbus ----
    private fun modbusReply(vararg objects: Pair<Int, String>): ByteArray {
        val pdu = ByteArrayOutputStream().apply {
            write(0x2B); write(0x0E); write(0x01); write(0x01); write(0x00); write(0x00); write(objects.size)
            objects.forEach { (id, v) -> write(id); write(v.length); write(v.toByteArray()) }
        }.toByteArray()
        return byteArrayOf(0, 1, 0, 0, 0, (pdu.size + 1).toByte(), 1) + pdu
    }
    @Test fun `modbus device id objects become a text line`() {
        assertEquals("modbus vendor=Schneider Electric product=BMX NOE 0100 revision=V2.60",
            ProbeParsers.modbus(modbusReply(0 to "Schneider Electric", 1 to "BMX NOE 0100", 2 to "V2.60")))
        assertEquals("modbus vendor=Acme", ProbeParsers.modbus(modbusReply(0 to "Acme")))
    }
    @Test fun `modbus exception and wrong replies`() {
        assertEquals("modbus exception 1", ProbeParsers.modbus(byteArrayOf(0, 1, 0, 0, 0, 3, 1, 0xAB.toByte(), 1)))
        assertNull(ProbeParsers.modbus("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())); assertNull(ProbeParsers.modbus(ByteArray(3)))
        val good = modbusReply(0 to "Acme", 1 to "Widget")
        for (cut in 0 until good.size) ProbeParsers.modbus(good.copyOf(cut))   // must not throw
        val lying = modbusReply(0 to "Acme").also { it[it.size - 5] = 0x7F }     // object length past the end
        assertEquals("modbus", ProbeParsers.modbus(lying))
    }

    @Test fun `unknown parser name gives null`() { assertNull(ProbeParsers.parse("nope", ByteArray(4))) }
}
