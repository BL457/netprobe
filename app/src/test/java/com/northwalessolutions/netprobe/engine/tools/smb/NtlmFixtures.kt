package com.northwalessolutions.netprobe.engine.tools.smb

import java.nio.ByteBuffer
import java.nio.ByteOrder

object NtlmFixtures {
    private fun le() = ByteOrder.LITTLE_ENDIAN
    private fun utf16(s: String) = s.toByteArray(Charsets.UTF_16LE)
    private fun av(id: Int, value: ByteArray) = ByteBuffer.allocate(4 + value.size).order(le()).putShort(id.toShort()).putShort(value.size.toShort()).put(value).array()

    /**
     * NTLM CHALLENGE (MS-NLMP 2.2.1.2): signature, type 2, empty TargetName at 56, flags with NEGOTIATE_VERSION, ServerChallenge
     * 0123456789abcdef, Reserved, TargetInfo fields (offset 56), Version 10.0 build 19041, then AV pairs: NbDomain "Domain",
     * NbComputer "Server", DnsComputer "srv.lan", Timestamp 0102030405060708, EOL.
     */
    fun challenge(): ByteArray {
        val ti = av(2, utf16("Domain")) + av(1, utf16("Server")) + av(3, utf16("srv.lan")) + av(7, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) + av(0, ByteArray(0))
        val head = ByteBuffer.allocate(56).order(le())
            .put("NTLMSSP\u0000".toByteArray(Charsets.ISO_8859_1)).putInt(2)
            .putShort(0).putShort(0).putInt(56)
            .putInt(Ntlm.negotiateFlags(false))
            .put(byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte())).putLong(0)
            .putShort(ti.size.toShort()).putShort(ti.size.toShort()).putInt(56)
            .put(byteArrayOf(10, 0, 0x61, 0x4a, 0, 0, 0, 0x0f))
            .array()
        return head + ti
    }
}
