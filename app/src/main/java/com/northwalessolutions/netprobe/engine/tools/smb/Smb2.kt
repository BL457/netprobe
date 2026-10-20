package com.northwalessolutions.netprobe.engine.tools.smb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** SMB2 NEGOTIATE and SESSION_SETUP only (MS-SMB2 2.2.1, 2.2.3 to 2.2.6); nothing else is ever built (spec 6.3). */
object Smb2 {
    const val HEADER_SIZE = 64
    const val NEGOTIATE = 0x0000
    const val SESSION_SETUP = 0x0001
    const val STATUS_SUCCESS = 0
    const val STATUS_MORE_PROCESSING_REQUIRED = 0xC0000016.toInt()
    val DIALECTS = listOf(0x0202, 0x0210, 0x0300, 0x0302)
    const val SIGNING_ENABLED = 0x0001
    const val SIGNING_REQUIRED = 0x0002
    const val SESSION_FLAG_IS_GUEST = 0x0001
    const val SESSION_FLAG_IS_NULL = 0x0002
    private val PROTOCOL_ID = byteArrayOf(0xFE.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte())
    private val SMB1_PROTOCOL_ID = byteArrayOf(0xFF.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte())
    private const val CREDIT_REQUEST = 1
    private const val NEGOTIATE_STRUCTURE = 36; private const val NEGOTIATE_RESPONSE_STRUCTURE = 65
    private const val NEGOTIATE_RESPONSE_READ = 6          // StructureSize, SecurityMode, DialectRevision
    private const val SESSION_SETUP_STRUCTURE = 25; private const val SESSION_SETUP_RESPONSE_STRUCTURE = 9
    private const val SESSION_SETUP_FIXED = 24; private const val SESSION_SETUP_RESPONSE_FIXED = 8
    private const val GUID_BYTES = 16; private const val SIGNATURE_BYTES = 16

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    fun creditCharge(dialect: Int): Int = if (dialect == 0x0202) 0 else 1

    /** The 64-byte SYNC header (2.2.1.2): ProtocolId, StructureSize 64, CreditCharge, Status 0, Command, CreditRequest 1, Flags 0,
     *  NextCommand 0, MessageId, Reserved, TreeId 0, SessionId, Signature zero (no request is signed). */
    fun header(command: Int, messageId: Long, sessionId: Long, creditCharge: Int): ByteArray = le(HEADER_SIZE)
        .put(PROTOCOL_ID).putShort(HEADER_SIZE.toShort()).putShort(creditCharge.toShort()).putInt(0)
        .putShort(command.toShort()).putShort(CREDIT_REQUEST.toShort()).putInt(0).putInt(0)
        .putLong(messageId).putInt(0).putInt(0).putLong(sessionId).put(ByteArray(SIGNATURE_BYTES)).array()

    /** NEGOTIATE request (2.2.3): four dialects, SecurityMode SIGNING_ENABLED, no capabilities, a ClientGuid, no negotiate contexts. */
    fun negotiate(messageId: Long, clientGuid: ByteArray): ByteArray {
        require(clientGuid.size == GUID_BYTES)
        val body = le(NEGOTIATE_STRUCTURE + DIALECTS.size * 2).putShort(NEGOTIATE_STRUCTURE.toShort()).putShort(DIALECTS.size.toShort())
            .putShort(SIGNING_ENABLED.toShort()).putShort(0).putInt(0).put(clientGuid).putLong(0)
        DIALECTS.forEach { body.putShort(it.toShort()) }
        return header(NEGOTIATE, messageId, 0, 0) + body.array()
    }
    /** SESSION_SETUP request (2.2.5): Flags 0, SecurityMode SIGNING_ENABLED, no capabilities, the blob at offset 88 (64 + 24). */
    fun sessionSetup(messageId: Long, sessionId: Long, creditCharge: Int, securityBlob: ByteArray): ByteArray {
        val body = le(SESSION_SETUP_FIXED + securityBlob.size).putShort(SESSION_SETUP_STRUCTURE.toShort()).put(0.toByte()).put(SIGNING_ENABLED.toByte())
            .putInt(0).putInt(0).putShort((HEADER_SIZE + SESSION_SETUP_FIXED).toShort()).putShort(securityBlob.size.toShort()).putLong(0).put(securityBlob)
        return header(SESSION_SETUP, messageId, sessionId, creditCharge) + body.array()
    }

    data class Header(val status: Int, val command: Int, val messageId: Long, val sessionId: Long)
    data class NegotiateResponse(val status: Int, val dialect: Int, val signingRequired: Boolean)
    class SessionSetupResponse(val status: Int, val sessionId: Long, val sessionFlags: Int, val securityBlob: ByteArray)

    /** Null unless the message holds a full SYNC header with the SMB2 ProtocolId and StructureSize 64 (an SMB1 reply is refused here). */
    fun parseHeader(m: ByteArray): Header? {
        if (m.size < HEADER_SIZE || !m.copyOfRange(0, 4).contentEquals(PROTOCOL_ID)) return null
        val b = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.getShort(4).toInt() and 0xFFFF) != HEADER_SIZE) return null
        return Header(b.getInt(8), b.getShort(12).toInt() and 0xFFFF, b.getLong(24), b.getLong(40))
    }
    /** An SMB1 message (MS-CIFS 2.2.3.1: 0xFF 'S' 'M' 'B'), the reply of a server that speaks no SMB2 dialect. */
    fun isSmb1(m: ByteArray): Boolean = m.size >= SMB1_PROTOCOL_ID.size && m.copyOfRange(0, SMB1_PROTOCOL_ID.size).contentEquals(SMB1_PROTOCOL_ID)
    /** A non-zero status means no dialect was agreed; the body is then an ERROR response and is not read. */
    fun parseNegotiate(m: ByteArray): NegotiateResponse? {
        val h = parseHeader(m) ?: return null
        if (h.command != NEGOTIATE) return null
        if (h.status != STATUS_SUCCESS) return NegotiateResponse(h.status, 0, false)
        if (m.size < HEADER_SIZE + NEGOTIATE_RESPONSE_READ) return null
        val b = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.getShort(HEADER_SIZE).toInt() and 0xFFFF) != NEGOTIATE_RESPONSE_STRUCTURE) return null
        val securityMode = b.getShort(HEADER_SIZE + 2).toInt() and 0xFFFF
        return NegotiateResponse(h.status, b.getShort(HEADER_SIZE + 4).toInt() and 0xFFFF, securityMode and SIGNING_REQUIRED != 0)
    }
    /** SESSION_SETUP response (2.2.6) for SUCCESS and MORE_PROCESSING_REQUIRED; any other status is an ERROR response with no flags or blob. */
    fun parseSessionSetup(m: ByteArray): SessionSetupResponse? {
        val h = parseHeader(m) ?: return null
        if (h.command != SESSION_SETUP) return null
        if (h.status != STATUS_SUCCESS && h.status != STATUS_MORE_PROCESSING_REQUIRED) return SessionSetupResponse(h.status, h.sessionId, 0, ByteArray(0))
        if (m.size < HEADER_SIZE + SESSION_SETUP_RESPONSE_FIXED) return null
        val b = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.getShort(HEADER_SIZE).toInt() and 0xFFFF) != SESSION_SETUP_RESPONSE_STRUCTURE) return null
        val flags = b.getShort(HEADER_SIZE + 2).toInt() and 0xFFFF
        val off = b.getShort(HEADER_SIZE + 4).toInt() and 0xFFFF; val len = b.getShort(HEADER_SIZE + 6).toInt() and 0xFFFF
        // The buffer must sit after the 8-byte fixed part and inside the message; both fields are 16-bit, so the sum cannot overflow.
        if (len > 0 && (off < HEADER_SIZE + SESSION_SETUP_RESPONSE_FIXED || off + len > m.size)) return null
        return SessionSetupResponse(h.status, h.sessionId, flags, if (len == 0) ByteArray(0) else m.copyOfRange(off, off + len))
    }
}
