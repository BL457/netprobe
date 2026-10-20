package com.northwalessolutions.netprobe.engine.tools.smb

import java.nio.ByteBuffer
import java.nio.ByteOrder

object SmbFixtures {
    const val STATUS_LOGON_FAILURE = 0xC000006D.toInt()
    const val STATUS_ACCOUNT_DISABLED = 0xC0000072.toInt()
    const val STATUS_ACCESS_DENIED = 0xC0000022.toInt()
    private const val STATUS_MORE_PROCESSING = 0xC0000016.toInt()
    private const val COMMAND_NEGOTIATE = 0x0000
    private const val COMMAND_SESSION_SETUP = 0x0001
    private fun le() = ByteOrder.LITTLE_ENDIAN
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun le16(v: Int) = ByteBuffer.allocate(2).order(le()).putShort(v.toShort()).array()
    private fun le32(v: Int) = ByteBuffer.allocate(4).order(le()).putInt(v).array()
    private fun le64(v: Long) = ByteBuffer.allocate(8).order(le()).putLong(v).array()

    /**
     * A response SYNC header (MS-SMB2 2.2.1.2), 64 bytes: ProtocolId fe 'S' 'M' 'B', StructureSize 64, CreditCharge 0, Status,
     * Command, CreditResponse 1, Flags SERVER_TO_REDIR, NextCommand 0, MessageId, Reserved 0, TreeId 0, SessionId, Signature zero.
     */
    private fun header(command: Int, status: Int, messageId: Long, sessionId: Long): ByteArray =
        hex("fe534d42" + "4000" + "0000") + le32(status) + le16(command) + hex("0100" + "01000000" + "00000000") + le64(messageId) +
            hex("00000000" + "00000000") + le64(sessionId) + ByteArray(16)

    /** NEGOTIATE response (MS-SMB2 2.2.4): StructureSize 65, SecurityMode, DialectRevision, then zeros to a 64-byte fixed part. */
    fun negotiateResponse(dialect: Int, securityMode: Int): ByteArray {
        val body = ByteBuffer.allocate(64).order(le()).putShort(65).putShort(securityMode.toShort()).putShort(dialect.toShort()).array()
        return header(COMMAND_NEGOTIATE, 0, 0, 0) + body
    }
    /** SMB2 ERROR response (MS-SMB2 2.2.2): StructureSize 9, ErrorContextCount 0, Reserved, ByteCount 0, one ErrorData byte. */
    fun errorResponse(command: Int, status: Int): ByteArray =
        header(command, status, 0, 0) + ByteBuffer.allocate(9).order(le()).putShort(9).array()
    /** SESSION_SETUP response (MS-SMB2 2.2.6): StructureSize 9, SessionFlags, SecurityBufferOffset 72, SecurityBufferLength, buffer. */
    fun sessionSetupResponse(status: Int, sessionId: Long, flags: Int, blob: ByteArray = ByteArray(0)): ByteArray {
        val body = ByteBuffer.allocate(8 + blob.size).order(le()).putShort(9).putShort(flags.toShort()).putShort(72).putShort(blob.size.toShort()).put(blob).array()
        return header(COMMAND_SESSION_SETUP, status, 1, sessionId) + body
    }
    /**
     * The challenge reply: a negTokenResp (RFC 4178 4.2.2) around the 122-byte CHALLENGE, its DER written out:
     * a1 81 80 (128 bytes) { 30 7e { a2 7c { 04 7a <CHALLENGE> } } }.
     */
    fun challengeResponse(sessionId: Long): ByteArray {
        val ntlm = NtlmFixtures.challenge()
        check(ntlm.size == CHALLENGE_BYTES) { "the DER lengths below assume a $CHALLENGE_BYTES-byte CHALLENGE, got ${ntlm.size}" }
        return sessionSetupResponse(STATUS_MORE_PROCESSING, sessionId, 0, hex("a18180" + "307e" + "a27c" + "047a") + ntlm)
    }
    private const val CHALLENGE_BYTES = 122
}
