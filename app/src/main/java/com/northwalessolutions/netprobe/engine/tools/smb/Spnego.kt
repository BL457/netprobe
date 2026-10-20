package com.northwalessolutions.netprobe.engine.tools.smb

import java.io.ByteArrayOutputStream

/** The minimal DER writer SPNEGO needs (X.690): definite lengths, short form under 128. */
object Der {
    private const val SHORT_MAX = 0x7F
    fun len(n: Int): ByteArray = when {
        n <= SHORT_MAX -> byteArrayOf(n.toByte())
        n <= 0xFF -> byteArrayOf(0x81.toByte(), n.toByte())
        n <= 0xFFFF -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }
    fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + len(content.size) + content
    fun oid(dotted: String): ByteArray {
        val p = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream(); out.write((p[0] * 40 + p[1]).toInt())
        for (arc in p.drop(2)) {
            val groups = ArrayList<Int>(); var v = arc
            do { groups.add((v and 0x7F).toInt()); v = v ushr 7 } while (v > 0)
            for (k in groups.indices.reversed()) out.write(groups[k] or if (k > 0) 0x80 else 0)
        }
        return tlv(0x06, out.toByteArray())
    }
}

/** SPNEGO (RFC 4178, MS-SPNG) wrapping of the NTLM tokens for SESSION_SETUP (spec 6.3). */
object Spnego {
    private const val SPNEGO_OID = "1.3.6.1.5.5.2"
    private const val NTLMSSP_OID = "1.3.6.1.4.1.311.2.2.10"
    private const val APPLICATION_0 = 0x60; private const val SEQUENCE = 0x30; private const val OCTET_STRING = 0x04
    private const val CTX0 = 0xa0; private const val CTX1 = 0xa1; private const val CTX2 = 0xa2
    private val NTLM_SIGNATURE = "NTLMSSP\u0000".toByteArray(Charsets.ISO_8859_1)

    /** InitialContextToken { thisMech spnego, [0] NegTokenInit { [0] mechTypes { NTLMSSP }, [2] mechToken } }. */
    fun negTokenInit(ntlm: ByteArray): ByteArray {
        val mechTypes = Der.tlv(CTX0, Der.tlv(SEQUENCE, Der.oid(NTLMSSP_OID)))
        val mechToken = Der.tlv(CTX2, Der.tlv(OCTET_STRING, ntlm))
        return Der.tlv(APPLICATION_0, Der.oid(SPNEGO_OID) + Der.tlv(CTX0, Der.tlv(SEQUENCE, mechTypes + mechToken)))
    }
    /** [1] NegTokenResp { [2] responseToken }. */
    fun negTokenResp(ntlm: ByteArray): ByteArray = Der.tlv(CTX1, Der.tlv(SEQUENCE, Der.tlv(CTX2, Der.tlv(OCTET_STRING, ntlm))))
    /** The NTLMSSP message inside a server's security buffer, found by its signature so DER variations between servers do not matter. */
    fun extractNtlm(blob: ByteArray): ByteArray? {
        for (i in 0..blob.size - NTLM_SIGNATURE.size) {
            if (NTLM_SIGNATURE.indices.all { blob[i + it] == NTLM_SIGNATURE[it] }) return blob.copyOfRange(i, blob.size)
        }
        return null
    }
}
