package com.northwalessolutions.netprobe.engine.tools.smb

import org.junit.Assert.*
import org.junit.Test

/** MS-SMB2 layouts pinned to bytes; parsers of each response form; garbage never throws. */
class SmbBytesTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val zeros16 = "00".repeat(16)

    @Test fun `negotiate request bytes`() {
        val header = "fe534d42" + "4000" + "0000" + "00000000" + "0000" + "0100" + "00000000" + "00000000" + "0000000000000000" + "00000000" + "00000000" + "0000000000000000" + zeros16
        val body = "2400" + "0400" + "0100" + "0000" + "00000000" + "11".repeat(16) + "0000000000000000" + "0202" + "1002" + "0003" + "0203"
        assertEquals(header + body, hex(Smb2.negotiate(messageId = 0, clientGuid = ByteArray(16) { 0x11 })))
    }
    @Test fun `session setup request bytes`() {
        val header = "fe534d42" + "4000" + "0100" + "00000000" + "0100" + "0100" + "00000000" + "00000000" + "0100000000000000" + "00000000" + "00000000" + "8877665544332211" + zeros16
        val body = "1900" + "00" + "01" + "00000000" + "00000000" + "5800" + "0300" + "0000000000000000" + "010203"
        assertEquals(header + body, hex(Smb2.sessionSetup(messageId = 1, sessionId = 0x1122334455667788L, creditCharge = 1, securityBlob = byteArrayOf(1, 2, 3))))
    }
    @Test fun `negotiate responses give dialect and the signing bit`() {
        val required = Smb2.parseNegotiate(SmbFixtures.negotiateResponse(dialect = 0x0302, securityMode = 0x0003))!!
        assertEquals(0, required.status); assertEquals(0x0302, required.dialect); assertTrue(required.signingRequired)
        val enabled = Smb2.parseNegotiate(SmbFixtures.negotiateResponse(dialect = 0x0210, securityMode = 0x0001))!!
        assertEquals(0x0210, enabled.dialect); assertFalse(enabled.signingRequired)
        for (d in listOf(0x0202, 0x0300)) assertEquals(d, Smb2.parseNegotiate(SmbFixtures.negotiateResponse(d, 1))!!.dialect)
        val refused = Smb2.parseNegotiate(SmbFixtures.errorResponse(Smb2.NEGOTIATE, 0xC00000BB.toInt()))!!
        assertEquals(0xC00000BB.toInt(), refused.status)
    }
    @Test fun `session setup responses give status, session id, flags and the blob`() {
        val more = Smb2.parseSessionSetup(SmbFixtures.challengeResponse(sessionId = 0x55L))!!
        assertEquals(Smb2.STATUS_MORE_PROCESSING_REQUIRED, more.status); assertEquals(0x55L, more.sessionId)
        assertNotNull(Ntlm.parseChallenge(Spnego.extractNtlm(more.securityBlob)!!))
        val nullSession = Smb2.parseSessionSetup(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x55L, Smb2.SESSION_FLAG_IS_NULL))!!
        assertEquals(Smb2.SESSION_FLAG_IS_NULL, nullSession.sessionFlags)
        val guest = Smb2.parseSessionSetup(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x56L, Smb2.SESSION_FLAG_IS_GUEST))!!
        assertEquals(Smb2.SESSION_FLAG_IS_GUEST, guest.sessionFlags)
        for (status in listOf(SmbFixtures.STATUS_LOGON_FAILURE, SmbFixtures.STATUS_ACCOUNT_DISABLED)) {
            val failed = Smb2.parseSessionSetup(SmbFixtures.errorResponse(Smb2.SESSION_SETUP, status))!!
            assertEquals(status, failed.status); assertEquals(0, failed.sessionFlags); assertEquals(0, failed.securityBlob.size)
        }
    }
    @Test fun `truncated or foreign messages parse to null`() {
        assertNull(Smb2.parseNegotiate(ByteArray(10))); assertNull(Smb2.parseSessionSetup(ByteArray(64)))
        assertNull(Smb2.parseHeader(byteArrayOf(0xFF.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte()) + ByteArray(60)))   // SMB1, not SMB2
        assertNull(Smb2.parseSessionSetup(SmbFixtures.sessionSetupResponse(0, 1, 0).copyOf(70)))
    }
    @Test fun `hostile header and buffer fields parse to null and nothing throws`() {
        val ok = SmbFixtures.sessionSetupResponse(0, 1, 0, byteArrayOf(1, 2, 3))
        assertNotNull(Smb2.parseSessionSetup(ok))
        assertNull(Smb2.parseHeader(ok.copyOf().also { it[4] = 0x41 }))                                     // header StructureSize 65
        assertNull(Smb2.parseSessionSetup(ok.copyOf().also { it[64] = 0x0a }))                              // body StructureSize 10
        assertNull(Smb2.parseSessionSetup(ok.copyOf().also { it[68] = 0x10; it[69] = 0 }))                   // SecurityBufferOffset inside the header
        assertNull(Smb2.parseSessionSetup(ok.copyOf().also { it[68] = 0x46; it[69] = 0 }))                   // offset 70, inside the fixed part
        assertNull(Smb2.parseSessionSetup(ok.copyOf().also { it[70] = 0x04; it[71] = 0 }))                   // SecurityBufferLength one past the end
        assertNull(Smb2.parseSessionSetup(ok.copyOf().also { it[70] = 0xff.toByte(); it[71] = 0xff.toByte() }))
        assertNull(Smb2.parseNegotiate(SmbFixtures.negotiateResponse(0x0302, 1).also { it[64] = 0x24 }))     // StructureSize 36, a request
        assertNull(Smb2.parseNegotiate(SmbFixtures.negotiateResponse(0x0302, 1).copyOf(68)))
        assertNull(Smb2.parseNegotiate(SmbFixtures.sessionSetupResponse(0, 1, 0)))                           // wrong command
        val random = java.util.Random(15)
        repeat(2_000) {
            val m = (if (it % 2 == 0) SmbFixtures.challengeResponse(0x55) else SmbFixtures.negotiateResponse(0x0302, 3)).copyOf(64 + random.nextInt(200))
            for (k in 4 until m.size) if (random.nextInt(8) == 0) m[k] = random.nextInt(256).toByte()
            Smb2.parseNegotiate(m); Smb2.parseSessionSetup(m)?.securityBlob?.let { blob -> Spnego.extractNtlm(blob)?.let(Ntlm::parseChallenge) }
        }
    }
}
