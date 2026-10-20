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
        assertEquals("1800180052000000", hex(a.copyOfRange(12, 20)))   // LmChallengeResponseFields: len 24, offset 82
        assertEquals("1e001e006a000000", hex(a.copyOfRange(20, 28)))   // NtChallengeResponseFields: len 30, offset 106
        assertEquals("0000000048000000", hex(a.copyOfRange(28, 36)))   // DomainNameFields: empty, offset 72
        assertEquals("0000000052000000", hex(a.copyOfRange(44, 52)))   // WorkstationFields: empty, offset 82
        assertEquals("0000000088000000", hex(a.copyOfRange(52, 60)))   // EncryptedRandomSessionKeyFields: empty, offset 136
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

    @Test fun `filetime of the unix epoch is the ms-nlmp value`() {
        assertEquals("00803ed5deb19d01", hex(Ntlm.filetime(0)))
    }

    private fun withInt(bytes: ByteArray, at: Int, v: Int) = bytes.also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(at, v) }
    private fun withShort(bytes: ByteArray, at: Int, v: Int) = bytes.also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(at, v.toShort()) }

    @Test fun `hostile challenge fields are null, never a throw`() {
        val fx = { NtlmFixtures.challenge() }
        assertNull("negative tiOff", Ntlm.parseChallenge(withInt(fx(), 44, -1)))
        assertNull("huge tiOff, Long guard", Ntlm.parseChallenge(withShort(withInt(fx(), 44, Int.MAX_VALUE), 40, 0xFFFF)))
        assertNull("tiOff just under Int.MAX", Ntlm.parseChallenge(withInt(fx(), 44, Int.MAX_VALUE - 10)))
        assertNull("tiLen 0xFFFF", Ntlm.parseChallenge(withShort(fx(), 40, 0xFFFF)))
        assertNull("AV pair length overrun", Ntlm.parseChallenge(withShort(fx(), 56 + 2, 0x7FFF)))
        assertNull("bad signature at a valid length", Ntlm.parseChallenge(fx().also { it[0] = 'X'.code.toByte() }))
        assertNull("targetInfo pointing back into the header", Ntlm.parseChallenge(withShort(withInt(fx(), 44, 0), 40, 56)))
        val full = fx()
        for (n in intArrayOf(0, 1, 7, 8, 12, 20, 32, 47, 55, 56, 57, 70, full.size - 1)) {
            assertNull("truncated at $n", Ntlm.parseChallenge(full.copyOf(n)))
        }
    }

    @Test fun `ten thousand mutated challenges never throw`() {
        val rnd = java.util.Random(0x5EED14L)
        val base = NtlmFixtures.challenge()
        repeat(FUZZ_ROUNDS) { round ->
            val m = when (round % 3) {
                0 -> base.copyOf().also { b -> repeat(1 + rnd.nextInt(8)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() } }
                1 -> base.copyOf(rnd.nextInt(base.size + 1))
                else -> ByteArray(rnd.nextInt(base.size * 2)).also { rnd.nextBytes(it); if (it.size >= 12) base.copyInto(it, 0, 0, 12) }
            }
            try { Ntlm.parseChallenge(m) } catch (t: Throwable) { fail("round $round threw $t on ${hex(m)}") }
        }
    }

    private companion object { const val FUZZ_ROUNDS = 10_000 }
}
