package com.northwalessolutions.netprobe.engine.tools.smb

import org.junit.Assert.*
import org.junit.Test

/** MS-SPNG / RFC 4178 wrapping and the DER helpers pinned to bytes. */
class SpnegoBytesTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun `spnego negTokenInit and negTokenResp bytes`() {
        assertEquals("6023" + "06062b0601050502" + "a019" + "3017" + "a00e" + "300c" + "060a2b06010401823702020a" + "a205" + "0403010203",
            hex(Spnego.negTokenInit(byteArrayOf(1, 2, 3))))
        assertEquals("a109" + "3007" + "a205" + "0403010203", hex(Spnego.negTokenResp(byteArrayOf(1, 2, 3))))
    }
    @Test fun `der lengths use the long form above 127`() {
        assertEquals("7f", hex(Der.len(127))); assertEquals("8180", hex(Der.len(128))); assertEquals("820100", hex(Der.len(256)))
        assertEquals("060a2b06010401823702020a", hex(Der.oid("1.3.6.1.4.1.311.2.2.10")))
    }
    @Test fun `extractNtlm finds the ntlmssp blob inside spnego`() {
        val ntlm = Ntlm.negotiate(Ntlm.negotiateFlags(false))
        assertEquals(hex(ntlm), hex(Spnego.extractNtlm(Spnego.negTokenInit(ntlm))!!))
        assertNull(Spnego.extractNtlm(byteArrayOf(1, 2, 3)))
    }

    @Test fun `extractNtlm never throws on short or random input`() {
        assertNull(Spnego.extractNtlm(ByteArray(0)))
        assertNull(Spnego.extractNtlm("NTLMSSP".toByteArray(Charsets.ISO_8859_1)))
        val rnd = java.util.Random(0x5EED14L)
        repeat(10_000) { round ->
            val b = ByteArray(rnd.nextInt(256)).also { rnd.nextBytes(it) }
            try { Spnego.extractNtlm(b) } catch (t: Throwable) { fail("round $round threw $t") }
        }
    }

    @Test fun `an accept-completed reply carries no ntlm token`() {
        assertNull(Spnego.extractNtlm(bytes("a1073005a0030a0100")))
    }

    @Test fun `a negTokenResp with a mechListMIC holding the signature still yields the challenge`() {
        val challenge = NtlmFixtures.challenge()
        val mic = "NTLMSSP\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(8) { 0x55 }
        val resp = Der.tlv(0xa1, Der.tlv(0x30,
            Der.tlv(0xa0, bytes("0a0101")) +                                  // negState accept-incomplete
            Der.tlv(0xa1, Der.oid("1.3.6.1.4.1.311.2.2.10")) +                // supportedMech NTLMSSP
            Der.tlv(0xa2, Der.tlv(0x04, challenge)) +                          // responseToken
            Der.tlv(0xa3, Der.tlv(0x04, mic))))                                // mechListMIC
        val token = Spnego.extractNtlm(resp)!!
        assertEquals(hex(challenge), hex(token.copyOfRange(0, challenge.size)))
        val c = Ntlm.parseChallenge(token)!!
        assertEquals("0123456789abcdef", hex(c.serverChallenge)); assertEquals("Server", c.names.netbiosComputer)
    }

    private fun bytes(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
