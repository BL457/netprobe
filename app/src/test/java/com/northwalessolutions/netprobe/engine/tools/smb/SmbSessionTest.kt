package com.northwalessolutions.netprobe.engine.tools.smb

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.*
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.control
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** spec 6.3 and 12.1: the sequence over scripted connections, the summaries, header sequencing, and nothing past SESSION_SETUP. */
class SmbSessionTest {
    private val target = ToolTarget(7, ToolTestSupport.ip("10.0.3.59"), 445, false, null)
    private class Script(vararg replies: ByteArray?) : SmbTransport {
        val sent = mutableListOf<ByteArray>(); var closed = false
        private val queue = ArrayDeque(replies.toList())
        override suspend fun exchange(request: ByteArray): ByteArray? { sent += request; return queue.removeFirstOrNull() }
        override fun close() { closed = true }
    }
    private fun le16(b: ByteArray, at: Int) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort(at).toInt() and 0xFFFF
    private fun le64(b: ByteArray, at: Int) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong(at)
    private suspend fun run(vararg connections: Script?): Triple<ToolResult, CollectingProgress, List<Script?>> {
        val queue = ArrayDeque(connections.toList()); val p = CollectingProgress()
        val r = SmbSessionTool(connect = { queue.removeFirstOrNull() }, randomBytes = { n -> ByteArray(n) { 0x5a } }, clock = { 0L }).run(target, ToolInputs.NONE, control(), p)
        return Triple(r, p, connections.toList())
    }
    private fun anonymous(final: ByteArray) = Script(SmbFixtures.negotiateResponse(0x0302, 3), SmbFixtures.challengeResponse(0x77), final)
    private fun guest(dialect: Int, final: ByteArray) = Script(SmbFixtures.negotiateResponse(dialect, 1), SmbFixtures.challengeResponse(0x78), final)

    @Test fun `a negotiate lost to a pause is retried once on a fresh connection`() = runTest {
        val c = ToolTestSupport.EpochControl()
        val frozen = object : SmbTransport {
            var closed = false
            override suspend fun exchange(request: ByteArray): ByteArray? { c.epoch++; return null }        // a pause began mid-read
            override fun close() { closed = true }
        }
        val queue = ArrayDeque<SmbTransport?>(listOf(frozen, anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL)),
            guest(0x0302, SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_LOGON_FAILURE))))
        val r = SmbSessionTool(connect = { queue.removeFirstOrNull() }, randomBytes = { n -> ByteArray(n) { 0x5a } }, clock = { 0L })
            .run(target, ToolInputs.NONE, c, CollectingProgress())
        assertEquals("SMB 3.0.2, signing required, anonymous allowed, guest refused", r.summary); assertTrue(frozen.closed)
    }
    @Test fun `anonymous allowed, guest refused, signing required`() = runTest {
        val (r, p, conns) = run(anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL)),
            guest(0x0302, SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_LOGON_FAILURE)))
        assertEquals(ToolRunStatus.DONE, r.status)
        assertEquals("SMB 3.0.2, signing required, anonymous allowed, guest refused", r.summary)
        assertEquals(listOf("SMB 3.0.2"), p.of(FindingKind.SMB_DIALECT).map { it.value }); assertEquals("Required", p.of(FindingKind.SMB_SIGNING).single().value)
        assertEquals(listOf("Anonymous allowed", "Guest refused"), p.of(FindingKind.SMB_SESSION).map { it.value })
        assertEquals(listOf("NetBIOS name" to "Server", "NetBIOS domain" to "Domain", "DNS name" to "srv.lan", "OS build (NTLM)" to "10.0.19041"),
            p.of(FindingKind.SMB_HOST).map { it.detail to it.value })
        assertEquals(listOf("smb.netbiosName=Server", "smb.dnsName=srv.lan", "smb.domain=Domain", "smb.osBuild=10.0.19041"), p.hints())
        assertTrue(conns.all { it!!.closed })
    }
    @Test fun `guest only, mapped to guest, reads as allowed`() = runTest {
        val (r, p, _) = run(anonymous(SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_ACCESS_DENIED)),
            guest(0x0302, SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x78, Smb2.SESSION_FLAG_IS_GUEST)))
        assertEquals(listOf("Anonymous refused", "Guest mapped to guest"), p.of(FindingKind.SMB_SESSION).map { it.value })
        assertEquals("SMB 3.0.2, signing required, anonymous refused, guest allowed", r.summary)
    }
    @Test fun `both refused on smb 2_1 without required signing`() = runTest {
        val first = Script(SmbFixtures.negotiateResponse(0x0210, 1), SmbFixtures.challengeResponse(0x77), SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_ACCESS_DENIED))
        val (r, _, _) = run(first, guest(0x0210, SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_ACCOUNT_DISABLED)))
        assertEquals("SMB 2.1, signing not required, anonymous refused, guest refused", r.summary)
    }
    @Test fun `guest allowed without the guest flag`() = runTest {
        val (r, p, _) = run(anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL)),
            guest(0x0302, SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x78, 0)))
        assertEquals("Guest allowed", p.of(FindingKind.SMB_SESSION).last().value); assertTrue(r.summary.endsWith("guest allowed"))
    }
    @Test fun `headers sequence message ids, carry the session id and charge credits by dialect`() = runTest {
        val first = anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL))
        val second = guest(0x0202, SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_LOGON_FAILURE))
        run(first, second)
        assertEquals(listOf(0L, 1L, 2L), first.sent.map { le64(it, 24) })
        assertEquals(listOf(0L, 0L, 0x77L), first.sent.map { le64(it, 40) })
        assertEquals(listOf(0, 1, 1), first.sent.map { le16(it, 6) })            // 3.0.2: CreditCharge 1 after NEGOTIATE
        assertEquals(listOf(0, 0, 0), second.sent.map { le16(it, 6) })           // 2.0.2: CreditCharge 0
        assertEquals(listOf(1, 1, 1), first.sent.map { le16(it, 14) })           // CreditRequest 1
    }
    @Test fun `the anonymous leg sends the exact anonymous authenticate`() = runTest {
        val first = anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL))
        run(first, null)
        val sentAuth = Spnego.extractNtlm(first.sent[2].copyOfRange(88, first.sent[2].size))!!
        assertArrayEquals(Ntlm.authenticate("", "", byteArrayOf(0), ByteArray(0), Ntlm.negotiateFlags(true)), sentAuth)
    }
    @Test fun `nothing but negotiate and session setup is ever written`() = runTest {
        val first = anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL))
        val second = guest(0x0302, SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x78, Smb2.SESSION_FLAG_IS_GUEST))
        run(first, second)
        val commands = (first.sent + second.sent).map { le16(it, 12) }
        assertEquals(listOf(0, 1, 1, 0, 1, 1), commands)
        assertTrue((first.sent + second.sent).all { Smb2.parseHeader(it) != null })
    }
    @Test fun `no connection or no reply is FAILED with no response`() = runTest {
        assertEquals("No SMB response on this port", run(null).first.summary)
        val silent = Script()
        val (r, _, _) = run(silent)
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("No SMB response on this port", r.summary); assertTrue(silent.closed)
    }
    @Test fun `a refused negotiate is no dialect in common`() = runTest {
        val (r, _, _) = run(Script(SmbFixtures.errorResponse(Smb2.NEGOTIATE, 0xC00000BB.toInt())))
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("No SMB2 dialect in common", r.summary)
    }

    // Task 14 report, "three things Task 15 must do": (b) the server's timestamp and raw target info go in the blob and LM is Z(24);
    // (c) NEGOTIATE_ANONYMOUS rides on the anonymous AUTHENTICATE only, never on an NTLM NEGOTIATE or the guest AUTHENTICATE.
    @Test fun `the guest leg sends the exact ntlmv2 authenticate with Z(24) lm and the server timestamp`() = runTest {
        val first = anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL))
        val second = guest(0x0302, SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x78, Smb2.SESSION_FLAG_IS_GUEST))
        run(first, second)
        val c = Ntlm.parseChallenge(NtlmFixtures.challenge())!!
        val ntowf = Ntlm.ntowfv2("guest", "", "")
        val blob = Ntlm.blob(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), ByteArray(8) { 0x5a }, c.targetInfo)
        val expected = Ntlm.authenticate("guest", "", ByteArray(24), Ntlm.ntResponseV2(ntowf, c.serverChallenge, blob), Ntlm.negotiateFlags(false))
        assertArrayEquals(expected, Spnego.extractNtlm(second.sent[2].copyOfRange(88, second.sent[2].size))!!)
        assertEquals(0x78L, le64(second.sent[2], 40))
        for (negotiate in listOf(first.sent[1], second.sent[1])) {
            val ntlm = Spnego.extractNtlm(negotiate.copyOfRange(88, negotiate.size))!!
            assertEquals(0, ByteBuffer.wrap(ntlm).order(ByteOrder.LITTLE_ENDIAN).getInt(12) and Ntlm.NEGOTIATE_ANONYMOUS)
        }
    }
    @Test fun `a reply lost mid anonymous leg to a pause is retried once on a fresh connection`() = runTest {
        val c = ToolTestSupport.EpochControl()
        val dropped = object : SmbTransport {
            var closed = false; var calls = 0
            override suspend fun exchange(request: ByteArray): ByteArray? {
                calls++
                return if (calls == 1) SmbFixtures.negotiateResponse(0x0302, 3) else { c.epoch++; null }    // the challenge lost to a pause
            }
            override fun close() { closed = true }
        }
        val queue = ArrayDeque<SmbTransport?>(listOf(dropped, anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL)),
            guest(0x0302, SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_LOGON_FAILURE))))
        val r = SmbSessionTool(connect = { queue.removeFirstOrNull() }, randomBytes = { n -> ByteArray(n) { 0x5a } }, clock = { 0L })
            .run(target, ToolInputs.NONE, c, CollectingProgress())
        assertEquals("SMB 3.0.2, signing required, anonymous allowed, guest refused", r.summary); assertTrue(dropped.closed)
    }
    @Test fun `a reply lost mid anonymous leg with no pause reads as refused`() = runTest {
        val first = Script(SmbFixtures.negotiateResponse(0x0302, 3), SmbFixtures.challengeResponse(0x77))     // no reply to the AUTHENTICATE
        val (r, _, _) = run(first, null)
        assertEquals("SMB 3.0.2, signing required, anonymous refused, guest refused", r.summary); assertTrue(first.closed)
    }
    @Test fun `server names are cleaned of control and format characters and blank names are dropped`() = runTest {
        val ntlm = NtlmFixtures.challenge()
        val server = "Server".toByteArray(Charsets.UTF_16LE); val hostile = "S\u001b\u202eerv".toByteArray(Charsets.UTF_16LE)
        val domain = "Domain".toByteArray(Charsets.UTF_16LE); val blank = "\u0007\u0007\u200b   ".toByteArray(Charsets.UTF_16LE)
        replace(ntlm, server, hostile); replace(ntlm, domain, blank)
        val reply = SmbFixtures.sessionSetupResponse(Smb2.STATUS_MORE_PROCESSING_REQUIRED, 0x77, 0, ntlm)
        val first = Script(SmbFixtures.negotiateResponse(0x0302, 3), reply, SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_ACCESS_DENIED))
        val (_, p, _) = run(first, null)
        assertEquals(listOf("NetBIOS name" to "Serv", "DNS name" to "srv.lan", "OS build (NTLM)" to "10.0.19041"), p.of(FindingKind.SMB_HOST).map { it.detail to it.value })
        assertEquals(listOf("smb.netbiosName=Serv", "smb.dnsName=srv.lan", "smb.osBuild=10.0.19041"), p.hints())
    }
    private fun replace(b: ByteArray, from: ByteArray, to: ByteArray) {
        val at = (0..b.size - from.size).first { i -> from.indices.all { b[i + it] == from[it] } }
        to.copyInto(b, at)
    }

    // Review fix round 1.
    /** Like [Script], but the exchange at [pauseAt] (0-based) begins a pause before its reply (which may be null) is returned. */
    private class PausingScript(private val c: ToolTestSupport.EpochControl, private val pauseAt: Int, vararg replies: ByteArray?) : SmbTransport {
        val sent = mutableListOf<ByteArray>(); var closed = false
        private val queue = ArrayDeque(replies.toList())
        override suspend fun exchange(request: ByteArray): ByteArray? { if (sent.size == pauseAt) c.epoch++; sent += request; return queue.removeFirstOrNull() }
        override fun close() { closed = true }
    }
    private suspend fun runWith(c: RunControl, vararg connections: SmbTransport?): ToolResult {
        val queue = ArrayDeque(connections.toList())
        return SmbSessionTool(connect = { queue.removeFirstOrNull() }, randomBytes = { n -> ByteArray(n) { 0x5a } }, clock = { 0L })
            .run(target, ToolInputs.NONE, c, CollectingProgress())
    }
    @Test fun `a reply lost mid guest leg to a pause is retried once`() = runTest {
        val c = ToolTestSupport.EpochControl()
        val lost = PausingScript(c, 1, SmbFixtures.negotiateResponse(0x0302, 1), null)                   // the CHALLENGE lost to a pause
        val retry = guest(0x0302, SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x78, 0))
        val r = runWith(c, anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL)), lost, retry)
        assertEquals("SMB 3.0.2, signing required, anonymous allowed, guest allowed", r.summary)
        assertTrue(lost.closed); assertEquals(3, retry.sent.size)
    }
    @Test fun `a genuine guest refusal during a pause is not retried`() = runTest {
        val c = ToolTestSupport.EpochControl()
        val refused = PausingScript(c, 2, SmbFixtures.negotiateResponse(0x0302, 1), SmbFixtures.challengeResponse(0x78),
            SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_LOGON_FAILURE))
        val spare = guest(0x0302, SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x78, 0))
        val r = runWith(c, anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL)), refused, spare)
        assertEquals("SMB 3.0.2, signing required, anonymous allowed, guest refused", r.summary)
        assertEquals("exactly one guest AUTHENTICATE", 3, refused.sent.size); assertTrue(spare.sent.isEmpty())
    }
    @Test fun `a timestamp that is not 8 bytes is ignored for the local clock and lmv2`() = runTest {
        val ti = av(2, "Domain".toByteArray(Charsets.UTF_16LE)) + av(7, byteArrayOf(1, 2, 3, 4)) + av(0, ByteArray(0))
        val sc = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte())
        val ntlm = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
            .put("NTLMSSP\u0000".toByteArray(Charsets.ISO_8859_1)).putInt(2).putShort(0).putShort(0).putInt(56)
            .putInt(Ntlm.negotiateFlags(false)).put(sc).putLong(0)
            .putShort(ti.size.toShort()).putShort(ti.size.toShort()).putInt(56).put(byteArrayOf(10, 0, 0x61, 0x4a, 0, 0, 0, 0x0f)).array() + ti
        val second = Script(SmbFixtures.negotiateResponse(0x0302, 1), SmbFixtures.sessionSetupResponse(Smb2.STATUS_MORE_PROCESSING_REQUIRED, 0x78, 0, ntlm),
            SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_LOGON_FAILURE))
        run(anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL)), second)
        val ntowf = Ntlm.ntowfv2("guest", "", ""); val cc = ByteArray(8) { 0x5a }
        val expected = Ntlm.authenticate("guest", "", Ntlm.lmV2(ntowf, sc, cc), Ntlm.ntResponseV2(ntowf, sc, Ntlm.blob(Ntlm.filetime(0L), cc, ti)), Ntlm.negotiateFlags(false))
        assertArrayEquals(expected, Spnego.extractNtlm(second.sent[2].copyOfRange(88, second.sent[2].size))!!)
    }
    private fun av(id: Int, value: ByteArray) = ByteBuffer.allocate(4 + value.size).order(ByteOrder.LITTLE_ENDIAN).putShort(id.toShort()).putShort(value.size.toShort()).put(value).array()
    @Test fun `an smb1 reply to negotiate is no dialect in common, other garbage is no response`() = runTest {
        val smb1 = byteArrayOf(0xFF.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte(), 0x72) + ByteArray(40)
        assertEquals("No SMB2 dialect in common", run(Script(smb1)).first.summary)
        assertEquals("No SMB response on this port", run(Script("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())).first.summary)
    }
}
