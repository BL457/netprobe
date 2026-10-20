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
}
