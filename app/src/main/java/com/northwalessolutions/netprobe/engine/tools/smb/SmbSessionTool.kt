package com.northwalessolutions.netprobe.engine.tools.smb

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.*
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import java.security.SecureRandom

/**
 * SMB2 session probe (spec 6.3): NEGOTIATE; on the same connection an anonymous SESSION_SETUP pair; on a new connection the same
 * NEGOTIATE and a guest SESSION_SETUP pair with an NTLMv2 response. Each connection is closed after its last SESSION_SETUP reply.
 * Nothing past SESSION_SETUP is ever sent (no TREE_CONNECT; share listing is Phase 3C).
 *
 * Success is read from the SMB2 Status and the SessionFlags only, never from the SPNEGO negResult, which servers omit or vary.
 * Each logon attempt leaves a Windows Security event (4624 or 4625) on the target.
 */
class SmbSessionTool(
    private val connect: suspend (ToolTarget) -> SmbTransport?,
    private val randomBytes: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } },
    private val clock: () -> Long = System::currentTimeMillis,
) : Tool {
    override val id = ToolId.SMB_SESSION
    override val name = ToolCopy.SMB_SESSION
    override fun applies(port: Int, service: ServiceInfo) = ToolApplicability.appliesTo(ToolId.SMB_SESSION, port, service)

    /** [lost] is true when an exchange of the leg got no reply at all, which a pause excuses (spec 4.8). */
    private class Leg(val allowed: Boolean, val names: Ntlm.TargetInfoNames?, val lost: Boolean)
    /** LOST: an exchange got no reply (or no connection was made), the only guest outcome a pause excuses. */
    private enum class Guest { ALLOWED, MAPPED, REFUSED, LOST }
    private sealed interface Negotiated {
        object Lost : Negotiated
        object NoDialect : Negotiated
        class Ok(val neg: Smb2.NegotiateResponse) : Negotiated
    }
    private sealed interface Opened {
        object NoResponse : Opened
        object NoDialect : Opened
        class Ok(val neg: Smb2.NegotiateResponse, val dialect: String, val anonymous: Leg) : Opened
    }
    private sealed interface Step {
        object Lost : Step
        object Refused : Step
        class Challenged(val sessionId: Long, val challenge: Ntlm.Challenge) : Step
    }

    /** The first connection: NEGOTIATE, then the anonymous SESSION_SETUP pair; the connection is always closed. */
    private suspend fun open(target: ToolTarget, control: RunControl): Opened {
        val conn = connect(target) ?: return Opened.NoResponse
        try {
            val n = when (val got = negotiate(conn)) {
                Negotiated.Lost -> return Opened.NoResponse
                Negotiated.NoDialect -> return Opened.NoDialect
                is Negotiated.Ok -> got.neg
            }
            val dialect = ToolCopy.dialectName(n.dialect) ?: return Opened.NoDialect
            control.gate()
            return Opened.Ok(n, dialect, anonymousLeg(conn, n))
        } finally { conn.close() }
    }

    override suspend fun run(target: ToolTarget, options: ToolOptions, control: RunControl, progress: ToolProgress): ToolResult {
        var found = 0
        suspend fun emit(kind: FindingKind, value: String, detail: String) { found++; progress.finding(Finding(kind, value, detail)); progress.update(found, null, found) }
        suspend fun hint(value: String) { progress.finding(Finding(FindingKind.HINT, value)) }

        control.gate()
        // spec 4.8 (plan review G5): a first connection that lost a reply while a pause began is retried once, on a fresh connection.
        val opened = control.excusedOnce<Opened>({ it === Opened.NoResponse || (it is Opened.Ok && it.anonymous.lost) }) { open(target, control) }
        val ok = when (opened) {
            Opened.NoResponse -> return ToolResult(ToolRunStatus.FAILED, ToolCopy.SMB_NO_RESPONSE)
            Opened.NoDialect -> return ToolResult(ToolRunStatus.FAILED, ToolCopy.SMB_NO_DIALECT)
            is Opened.Ok -> opened
        }
        val neg = ok.neg; val anonymous = ok.anonymous
        val names = anonymous.names?.let(::cleaned)

        emit(FindingKind.SMB_DIALECT, ok.dialect, ToolCopy.DIALECT)
        emit(FindingKind.SMB_SIGNING, if (neg.signingRequired) ToolCopy.REQUIRED else ToolCopy.ENABLED_NOT_REQUIRED, ToolCopy.SIGNING)
        names?.let { n ->
            n.netbiosComputer?.let { emit(FindingKind.SMB_HOST, it, ToolCopy.NETBIOS_NAME) }
            n.netbiosDomain?.let { emit(FindingKind.SMB_HOST, it, ToolCopy.NETBIOS_DOMAIN) }
            n.dnsComputer?.let { emit(FindingKind.SMB_HOST, it, ToolCopy.DNS_NAME) }
            n.dnsDomain?.let { emit(FindingKind.SMB_HOST, it, ToolCopy.DNS_DOMAIN) }
            n.osBuild?.let { emit(FindingKind.SMB_HOST, it, ToolCopy.OS_BUILD) }
        }
        emit(FindingKind.SMB_SESSION, if (anonymous.allowed) ToolCopy.ANONYMOUS_ALLOWED else ToolCopy.ANONYMOUS_REFUSED, ToolCopy.SESSION)

        control.gate()
        // Only a lost reply is excused: a genuine refusal is never retried, since every retry is another failed logon (4625) on the host.
        val guest = control.excusedOnce<Guest>({ it == Guest.LOST }) {
            connect(target)?.let { conn ->
                try {
                    when (val again = negotiate(conn)) {
                        Negotiated.Lost -> Guest.LOST
                        Negotiated.NoDialect -> Guest.REFUSED
                        is Negotiated.Ok -> { control.gate(); guestLeg(conn, again.neg) }
                    }
                } finally { conn.close() }
            } ?: Guest.LOST
        }
        val guestAllowed = guest == Guest.ALLOWED || guest == Guest.MAPPED
        emit(FindingKind.SMB_SESSION, when (guest) { Guest.ALLOWED -> ToolCopy.GUEST_ALLOWED; Guest.MAPPED -> ToolCopy.GUEST_MAPPED; else -> ToolCopy.GUEST_REFUSED }, ToolCopy.SESSION)

        names?.let { n ->
            n.netbiosComputer?.let { hint("smb.netbiosName=$it") }
            n.dnsComputer?.let { hint("smb.dnsName=$it") }
            (n.netbiosDomain ?: n.dnsDomain)?.let { hint("smb.domain=$it") }
            n.osBuild?.let { hint("smb.osBuild=$it") }
        }
        return ToolResult(ToolRunStatus.DONE, ToolCopy.smbSummary(ok.dialect, neg.signingRequired, anonymous.allowed, guestAllowed))
    }

    /** No reply, or a reply that is neither SMB2 nor SMB1, is Lost; an SMB1 reply, a refusal or an unknown dialect is NoDialect. */
    private suspend fun negotiate(conn: SmbTransport): Negotiated {
        val raw = conn.exchange(Smb2.negotiate(0, randomBytes(GUID_BYTES))) ?: return Negotiated.Lost
        val n = Smb2.parseNegotiate(raw) ?: return if (Smb2.isSmb1(raw)) Negotiated.NoDialect else Negotiated.Lost
        if (n.status != Smb2.STATUS_SUCCESS || n.dialect !in Smb2.DIALECTS) return Negotiated.NoDialect
        return Negotiated.Ok(n)
    }

    /**
     * MessageId 1: SESSION_SETUP with the NTLM NEGOTIATE (no NEGOTIATE_ANONYMOUS: that flag goes on the anonymous AUTHENTICATE only).
     * The CHALLENGE is read only from a STATUS_MORE_PROCESSING_REQUIRED reply.
     */
    private suspend fun challengeStep(conn: SmbTransport, neg: Smb2.NegotiateResponse): Step {
        val raw = conn.exchange(Smb2.sessionSetup(1, 0, Smb2.creditCharge(neg.dialect), Spnego.negTokenInit(Ntlm.negotiate(Ntlm.negotiateFlags(anonymous = false)))))
            ?: return Step.Lost
        val reply = Smb2.parseSessionSetup(raw) ?: return Step.Refused
        if (reply.status != Smb2.STATUS_MORE_PROCESSING_REQUIRED) return Step.Refused
        val challenge = Spnego.extractNtlm(reply.securityBlob)?.let(Ntlm::parseChallenge) ?: return Step.Refused
        return Step.Challenged(reply.sessionId, challenge)
    }

    /** The anonymous leg (MS-NLMP 3.2.5.1.2): empty user and domain, an empty NT response, one zero LM byte, NEGOTIATE_ANONYMOUS set. */
    private suspend fun anonymousLeg(conn: SmbTransport, neg: Smb2.NegotiateResponse): Leg {
        val step = challengeStep(conn, neg)
        if (step !is Step.Challenged) return Leg(false, null, lost = step === Step.Lost)
        val auth = Ntlm.authenticate("", "", byteArrayOf(0), ByteArray(0), Ntlm.negotiateFlags(anonymous = true))
        val raw = conn.exchange(Smb2.sessionSetup(2, step.sessionId, Smb2.creditCharge(neg.dialect), Spnego.negTokenResp(auth)))
            ?: return Leg(false, step.challenge.names, lost = true)
        return Leg(Smb2.parseSessionSetup(raw)?.status == Smb2.STATUS_SUCCESS, step.challenge.names, lost = false)
    }

    /**
     * The guest leg: user "guest", empty domain and password, NTLMv2 over the CHALLENGE's raw target info, no MIC.
     * With the server's MsvAvTimestamp that timestamp goes in the blob and LM is Z(24) (MS-NLMP 3.1.5.1.2); without it, the local clock and LMv2.
     */
    private suspend fun guestLeg(conn: SmbTransport, neg: Smb2.NegotiateResponse): Guest {
        val step = when (val got = challengeStep(conn, neg)) {
            Step.Lost -> return Guest.LOST
            Step.Refused -> return Guest.REFUSED
            is Step.Challenged -> got
        }
        val challenge = step.challenge
        val ntowf = Ntlm.ntowfv2(GUEST_USER, "", "")
        val clientChallenge = randomBytes(CLIENT_CHALLENGE_BYTES)
        val serverTime = challenge.names.timestamp?.takeIf { it.size == FILETIME_BYTES }     // a FILETIME is 8 bytes; anything else is ignored
        val blob = Ntlm.blob(serverTime ?: Ntlm.filetime(clock()), clientChallenge, challenge.targetInfo)
        val lm = if (serverTime != null) ByteArray(LM_RESPONSE_BYTES) else Ntlm.lmV2(ntowf, challenge.serverChallenge, clientChallenge)
        val auth = Ntlm.authenticate(GUEST_USER, "", lm, Ntlm.ntResponseV2(ntowf, challenge.serverChallenge, blob), Ntlm.negotiateFlags(anonymous = false))
        val raw = conn.exchange(Smb2.sessionSetup(2, step.sessionId, Smb2.creditCharge(neg.dialect), Spnego.negTokenResp(auth))) ?: return Guest.LOST
        val reply = Smb2.parseSessionSetup(raw) ?: return Guest.REFUSED
        if (reply.status != Smb2.STATUS_SUCCESS) return Guest.REFUSED
        return if (reply.sessionFlags and Smb2.SESSION_FLAG_IS_GUEST != 0) Guest.MAPPED else Guest.ALLOWED
    }

    companion object {
        const val SMB_TIMEOUT_MS = 3000
        /** Cap on any server-supplied name before it reaches a finding or a hint. */
        const val SMB_MAX_TEXT = 255
        private const val GUID_BYTES = 16; private const val CLIENT_CHALLENGE_BYTES = 8; private const val GUEST_USER = "guest"
        private const val LM_RESPONSE_BYTES = 24; private const val FILETIME_BYTES = 8

        /** The production connection: Probe.connect binds through the context's BoundNetwork (Wi-Fi past a VPN), kept open for the transport. */
        suspend fun tcpConnect(target: ToolTarget, timeoutMs: Int = SMB_TIMEOUT_MS): SmbTransport? =
            (Probe.connect(target.ip, target.port, timeoutMs, keepOpen = true) as? Probe.Outcome.Connected)?.socket?.let { TcpSmbTransport(it, timeoutMs) }

        /** Control and format characters removed (no newline, escape or bidi override reaches a finding or a hint), capped; blank becomes null. */
        private fun clean(text: String?): String? = text
            ?.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }?.take(SMB_MAX_TEXT)
            ?.let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
            ?.trim()?.ifEmpty { null }
        private fun cleaned(n: Ntlm.TargetInfoNames) =
            n.copy(netbiosComputer = clean(n.netbiosComputer), netbiosDomain = clean(n.netbiosDomain), dnsComputer = clean(n.dnsComputer),
                dnsDomain = clean(n.dnsDomain), osBuild = clean(n.osBuild))
    }
}
