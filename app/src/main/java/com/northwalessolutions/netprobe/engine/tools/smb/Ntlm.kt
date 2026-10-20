package com.northwalessolutions.netprobe.engine.tools.smb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** NTLM messages and NTLMv2 responses (MS-NLMP), hand-written with no library (spec 6.3). */
object Ntlm {
    const val NEGOTIATE_UNICODE = 0x00000001
    const val REQUEST_TARGET = 0x00000004
    const val NEGOTIATE_NTLM = 0x00000200
    const val NEGOTIATE_ANONYMOUS = 0x00000800
    const val NEGOTIATE_ALWAYS_SIGN = 0x00008000
    const val NEGOTIATE_EXTENDED_SESSIONSECURITY = 0x00080000
    const val NEGOTIATE_TARGET_INFO = 0x00800000
    const val NEGOTIATE_VERSION = 0x02000000
    const val NEGOTIATE_128 = 0x20000000
    const val NEGOTIATE_56 = Int.MIN_VALUE                    // 0x80000000
    private val SIGNATURE = "NTLMSSP\u0000".toByteArray(Charsets.ISO_8859_1)
    /** Version (MS-NLMP 2.2.2.10): 6.1 build 0, NTLMSSP_REVISION_W2K3 (15). */
    private val VERSION = byteArrayOf(6, 1, 0, 0, 0, 0, 0, 0x0f)
    private const val TYPE_NEGOTIATE = 1; private const val TYPE_CHALLENGE = 2; private const val TYPE_AUTHENTICATE = 3
    private const val NEGOTIATE_SIZE = 40; private const val AUTHENTICATE_HEADER = 72; private const val CHALLENGE_HEADER = 56
    private const val AV_EOL = 0; private const val AV_NB_COMPUTER = 1; private const val AV_NB_DOMAIN = 2
    private const val AV_DNS_COMPUTER = 3; private const val AV_DNS_DOMAIN = 4; private const val AV_TIMESTAMP = 7
    private const val FILETIME_EPOCH_OFFSET_MS = 11_644_473_600_000L; private const val FILETIME_TICKS_PER_MS = 10_000L

    fun negotiateFlags(anonymous: Boolean): Int {
        val base = NEGOTIATE_UNICODE or REQUEST_TARGET or NEGOTIATE_NTLM or NEGOTIATE_ALWAYS_SIGN or NEGOTIATE_EXTENDED_SESSIONSECURITY or
            NEGOTIATE_TARGET_INFO or NEGOTIATE_VERSION or NEGOTIATE_128 or NEGOTIATE_56
        return if (anonymous) base or NEGOTIATE_ANONYMOUS else base
    }

    private fun buf(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun ByteBuffer.field(length: Int, offset: Int): ByteBuffer = putShort(length.toShort()).putShort(length.toShort()).putInt(offset)
    private fun unicode(s: String) = s.toByteArray(Charsets.UTF_16LE)
    private fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacMD5").run { init(SecretKeySpec(key, "HmacMD5")); doFinal(data) }

    /** NEGOTIATE_MESSAGE (2.2.1.1): no domain or workstation supplied; empty fields point at the end of the 40-byte message. */
    fun negotiate(flags: Int): ByteArray =
        buf(NEGOTIATE_SIZE).put(SIGNATURE).putInt(TYPE_NEGOTIATE).putInt(flags).field(0, NEGOTIATE_SIZE).field(0, NEGOTIATE_SIZE).put(VERSION).array()

    data class TargetInfoNames(val netbiosComputer: String?, val netbiosDomain: String?, val dnsComputer: String?, val dnsDomain: String?,
                               val timestamp: ByteArray?, val osBuild: String?)
    class Challenge(val serverChallenge: ByteArray, val targetInfo: ByteArray, val flags: Int, val names: TargetInfoNames)

    /** CHALLENGE_MESSAGE (2.2.1.2): flags at 20, ServerChallenge at 24, TargetInfoFields at 40, Version at 48; AV pairs (2.2.2.1). Null when malformed. */
    fun parseChallenge(bytes: ByteArray): Challenge? = runCatching {
        require(bytes.size >= CHALLENGE_HEADER && bytes.copyOfRange(0, 8).contentEquals(SIGNATURE))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.getInt(8) == TYPE_CHALLENGE)
        val flags = b.getInt(20)
        val serverChallenge = bytes.copyOfRange(24, 32)
        val tiLen = b.getShort(40).toInt() and 0xFFFF; val tiOff = b.getInt(44)
        require(tiOff >= 0 && tiOff.toLong() + tiLen <= bytes.size)
        val targetInfo = bytes.copyOfRange(tiOff, tiOff + tiLen)
        val osBuild = if (flags and NEGOTIATE_VERSION != 0) "${bytes[48].toInt() and 0xff}.${bytes[49].toInt() and 0xff}.${b.getShort(50).toInt() and 0xFFFF}" else null
        var nbComputer: String? = null; var nbDomain: String? = null; var dnsComputer: String? = null; var dnsDomain: String? = null; var ts: ByteArray? = null
        var i = 0
        val t = ByteBuffer.wrap(targetInfo).order(ByteOrder.LITTLE_ENDIAN)
        while (i + 4 <= targetInfo.size) {
            val id = t.getShort(i).toInt() and 0xFFFF; val len = t.getShort(i + 2).toInt() and 0xFFFF
            require(i + 4 + len <= targetInfo.size)
            val value = targetInfo.copyOfRange(i + 4, i + 4 + len)
            when (id) {
                AV_EOL -> break
                AV_NB_COMPUTER -> nbComputer = String(value, Charsets.UTF_16LE)
                AV_NB_DOMAIN -> nbDomain = String(value, Charsets.UTF_16LE)
                AV_DNS_COMPUTER -> dnsComputer = String(value, Charsets.UTF_16LE)
                AV_DNS_DOMAIN -> dnsDomain = String(value, Charsets.UTF_16LE)
                AV_TIMESTAMP -> ts = value
            }
            i += 4 + len
        }
        Challenge(serverChallenge, targetInfo, flags, TargetInfoNames(nbComputer, nbDomain, dnsComputer, dnsDomain, ts, osBuild))
    }.getOrNull()

    /** NTOWFv2 (3.3.2) = HMAC_MD5(MD4(UNICODE(password)), UNICODE(Uppercase(user) + domain)). */
    fun ntowfv2(user: String, domain: String, password: String): ByteArray = hmacMd5(Md4.digest(unicode(password)), unicode(user.uppercase() + domain))
    /** LMv2 (3.3.2) = HMAC_MD5(NTOWFv2, ServerChallenge || ClientChallenge) || ClientChallenge. */
    fun lmV2(ntowf: ByteArray, serverChallenge: ByteArray, clientChallenge: ByteArray): ByteArray = hmacMd5(ntowf, serverChallenge + clientChallenge) + clientChallenge
    /** The NTLMv2 temp blob (3.3.2): 0x01 0x01, Z(6), timestamp, client challenge, Z(4), target info, Z(4). */
    fun blob(timestamp: ByteArray, clientChallenge: ByteArray, targetInfo: ByteArray): ByteArray =
        byteArrayOf(1, 1, 0, 0, 0, 0, 0, 0) + timestamp + clientChallenge + ByteArray(4) + targetInfo + ByteArray(4)
    /** NtChallengeResponse = NTProofStr || blob, NTProofStr = HMAC_MD5(NTOWFv2, ServerChallenge || blob). */
    fun ntResponseV2(ntowf: ByteArray, serverChallenge: ByteArray, blob: ByteArray): ByteArray = hmacMd5(ntowf, serverChallenge + blob) + blob
    /** FILETIME little-endian: 100 ns ticks since 1601-01-01. */
    fun filetime(ms: Long): ByteArray = buf(8).putLong((ms + FILETIME_EPOCH_OFFSET_MS) * FILETIME_TICKS_PER_MS).array()

    /**
     * AUTHENTICATE_MESSAGE (2.2.1.3) without a MIC: a 72-byte header, then the payload in the order domain, user, workstation (empty),
     * LmChallengeResponse, NtChallengeResponse, with an empty EncryptedRandomSessionKey at the end.
     */
    fun authenticate(user: String, domain: String, lm: ByteArray, nt: ByteArray, flags: Int): ByteArray {
        val d = unicode(domain); val u = unicode(user)
        val dOff = AUTHENTICATE_HEADER; val uOff = dOff + d.size; val wOff = uOff + u.size
        val lmOff = wOff; val ntOff = lmOff + lm.size; val keyOff = ntOff + nt.size
        val header = buf(AUTHENTICATE_HEADER).put(SIGNATURE).putInt(TYPE_AUTHENTICATE)
            .field(lm.size, lmOff).field(nt.size, ntOff).field(d.size, dOff).field(u.size, uOff).field(0, wOff).field(0, keyOff)
            .putInt(flags).put(VERSION).array()
        return header + d + u + lm + nt
    }
}
