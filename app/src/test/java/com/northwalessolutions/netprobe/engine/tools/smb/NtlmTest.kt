package com.northwalessolutions.netprobe.engine.tools.smb

import org.junit.Assert.*
import org.junit.Test

/** MS-NLMP 4.2.4 published vectors, and the exact NEGOTIATE and anonymous AUTHENTICATE bytes. */
class NtlmTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bytes(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val serverChallenge = bytes("0123456789abcdef")
    private val clientChallenge = bytes("aaaaaaaaaaaaaaaa")
    private val ntowf get() = Ntlm.ntowfv2("User", "Domain", "Password")

    @Test fun `ntowfv2 matches ms-nlmp 4_2_4`() { assertEquals("0c868a403bfd7a93a3001ef22ef02e3f", hex(ntowf)) }
    @Test fun `lmv2 matches ms-nlmp 4_2_4`() {
        assertEquals("86c35097ac9cec102554764a57cccc19aaaaaaaaaaaaaaaa", hex(Ntlm.lmV2(ntowf, serverChallenge, clientChallenge)))
    }
    @Test fun `ntproofstr matches ms-nlmp 4_2_4`() {
        val av = { id: Int, s: String -> val v = s.toByteArray(Charsets.UTF_16LE); byteArrayOf(id.toByte(), 0, v.size.toByte(), 0) + v }
        val targetInfo = av(2, "Domain") + av(1, "Server") + byteArrayOf(0, 0, 0, 0)
        val blob = Ntlm.blob(ByteArray(8), clientChallenge, targetInfo)
        assertEquals("68cd0ab851e51c96aabc927bebef6a1c", hex(Ntlm.ntResponseV2(ntowf, serverChallenge, blob).copyOfRange(0, 16)))
        assertEquals(hex(blob), hex(Ntlm.ntResponseV2(ntowf, serverChallenge, blob).copyOfRange(16, 16 + blob.size)))
    }
    @Test fun `the flags are the spec's set`() {
        assertEquals(0xA2888205.toInt(), Ntlm.negotiateFlags(anonymous = false))
        assertEquals(0xA2888A05.toInt(), Ntlm.negotiateFlags(anonymous = true))
    }
    @Test fun `negotiate message bytes`() {
        assertEquals("4e544c4d53535000" + "01000000" + "058288a2" + "0000" + "0000" + "28000000" + "0000" + "0000" + "28000000" + "060100000000000f",
            hex(Ntlm.negotiate(Ntlm.negotiateFlags(false))))
    }
    @Test fun `anonymous authenticate bytes per ms-nlmp 3_2_5_1_2`() {
        val expected = "4e544c4d53535000" + "03000000" +
            "0100" + "0100" + "48000000" +       // LmChallengeResponse: one zero byte at 72
            "0000" + "0000" + "49000000" +       // NtChallengeResponse: empty, at 73
            "0000" + "0000" + "48000000" +       // DomainName: empty
            "0000" + "0000" + "48000000" +       // UserName: empty
            "0000" + "0000" + "48000000" +       // Workstation: empty
            "0000" + "0000" + "49000000" +       // EncryptedRandomSessionKey: empty
            "058a88a2" + "060100000000000f" + "00"
        assertEquals(expected, hex(Ntlm.authenticate("", "", byteArrayOf(0), ByteArray(0), Ntlm.negotiateFlags(true))))
    }
    @Test fun `a guest authenticate places user and responses after the 72 byte header`() {
        val a = Ntlm.authenticate("guest", "", ByteArray(24) { 1 }, ByteArray(30) { 2 }, Ntlm.negotiateFlags(false))
        assertEquals(72 + 10 + 24 + 30, a.size)
        assertEquals("0a000a0048000000", hex(a.copyOfRange(36, 44)))   // UserNameFields: len 10, offset 72
        assertEquals("guest", String(a.copyOfRange(72, 82), Charsets.UTF_16LE))
    }
    @Test fun `a challenge parses its names, timestamp and os build`() {
        val c = Ntlm.parseChallenge(NtlmFixtures.challenge())!!
        assertEquals("0123456789abcdef", hex(c.serverChallenge))
        assertEquals("Server", c.names.netbiosComputer); assertEquals("Domain", c.names.netbiosDomain); assertEquals("srv.lan", c.names.dnsComputer)
        assertNull(c.names.dnsDomain); assertEquals("0102030405060708", hex(c.names.timestamp!!)); assertEquals("10.0.19041", c.names.osBuild)
    }
    @Test fun `garbage and truncated challenges are null`() {
        assertNull(Ntlm.parseChallenge(ByteArray(10))); assertNull(Ntlm.parseChallenge(NtlmFixtures.challenge().copyOf(60)))
        assertNull(Ntlm.parseChallenge(NtlmFixtures.challenge().also { it[8] = 3 }))
    }
}
