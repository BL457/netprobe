package com.northwalessolutions.netprobe.engine.script

import java.io.Closeable
import java.net.InetAddress

/**
 * An address the network scope has approved (D4, G2). Only [ScopeGate] implementations create one, and every transport call takes one, so a
 * connection is always made to the exact address that passed the check and nothing is resolved a second time.
 */
class Cleared internal constructor(
    val address: InetAddress, val host: String,
    /** True only for the current subnet's directed broadcast address, approved for a declared broadcast tool (spec section 11). */
    val broadcast: Boolean = false,
)

/** A DNS name the scope allows to be looked up. */
class ClearedName internal constructor(val name: String)

/** The check every transport call goes through (D4, G2, G3). The shipped implementation is NetworkScopeGuard. */
interface ScopeGate {
    /** Approves a connection to [host] (a name or a canonical literal) and returns the one address to connect to. Throws [ScopeViolation]. */
    fun clearConnect(host: String, port: Int): Cleared
    /**
     * Approves a UDP datagram to [host]. The same as [clearConnect] except that the current subnet's directed broadcast address is approved
     * (and marked [Cleared.broadcast]) for a LAN tool that declared `broadcast: true`. Throws [ScopeViolation].
     */
    fun clearDatagram(host: String, port: Int): Cleared = clearConnect(host, port)
    /** Approves a DNS lookup of [name]. Throws [ScopeViolation]. */
    fun clearName(name: String): ClearedName
    /** Approves a reverse lookup of the literal [ip]. Throws [ScopeViolation]. */
    fun clearReverse(ip: String): Cleared
}

/** Refuses everything. The default until a real guard is passed in, so a missing scope can never mean an open scope. */
object DenyAllGate : ScopeGate {
    private val refusal get() = ScopeViolation(com.northwalessolutions.netprobe.ui.tools.ScriptCopy.NO_SCOPE)
    override fun clearConnect(host: String, port: Int): Cleared = throw refusal
    override fun clearName(name: String): ClearedName = throw refusal
    override fun clearReverse(ip: String): Cleared = throw refusal
}

/** What a transport may do for a run: track sockets so Stop can close them all (G5), and check whether the run is still wanted. */
interface IoScope {
    fun track(c: Closeable)
    fun untrack(c: Closeable)
    /** Throws [ScriptAbort] when the run has been cancelled or has used up a budget. */
    fun ensureAlive()
}

class HttpCall(
    val method: String, val pathAndQuery: String, val tls: Boolean, val port: Int, val hostHeader: String, val sni: String?,
    val headers: List<Pair<String, String>>, val body: ByteArray, val timeoutMs: Int,
)
class HttpReply(val status: Int, val reason: String, val headers: List<Pair<String, String>>, val body: ByteArray, val truncated: Boolean, val timeMs: Int)

class StreamCall(
    val port: Int, val send: ByteArray, val timeoutMs: Int, val readUntil: ByteArray?, val maxBytes: Int, val tls: Boolean, val sni: String?,
)
class StreamReply(val bytes: ByteArray, val closed: Boolean, val timeMs: Int)

interface StreamConnection : Closeable {
    fun send(bytes: ByteArray)
    fun recv(maxBytes: Int, timeoutMs: Int, readUntil: ByteArray?): StreamReply
}

class DatagramCall(val port: Int, val send: ByteArray, val timeoutMs: Int, val maxBytes: Int)
/** One datagram sent and forgotten: no reply is waited for. */
class DatagramSend(val port: Int, val data: ByteArray)
/** [bytes] is null when no datagram arrived within the timeout. */
class DatagramReply(val bytes: ByteArray?, val timeMs: Int)

class CertFacts(
    val subject: String, val issuer: String, val sans: List<String>, val notBefore: Long, val notAfter: Long, val keyAlgorithm: String,
    val keyBits: Int?, val signatureAlgorithm: String, val serial: String, val sha256: String, val sha1: String, val selfSigned: Boolean,
)
class CertChain(val protocol: String, val cipherSuite: String, val certificates: List<CertFacts>)

/**
 * The network side of the host API. Calls block the calling script (or pool) thread, which is never the main thread and never the tool
 * dispatcher. Every call takes an approved [Cleared] address and an [IoScope]; none follows redirects (the host API does, hop by hop).
 */
interface ScriptTransport {
    fun resolve(name: ClearedName, io: IoScope): List<InetAddress>
    fun reverse(to: Cleared, io: IoScope): String?
    fun http(to: Cleared, call: HttpCall, io: IoScope): HttpReply
    fun exchange(to: Cleared, call: StreamCall, io: IoScope): StreamReply
    fun connect(to: Cleared, call: StreamCall, io: IoScope): StreamConnection
    fun datagram(to: Cleared, call: DatagramCall, io: IoScope): DatagramReply
    /** Sends one datagram and returns at once (ctx.udp.send). A broadcast destination needs SO_BROADCAST, which the transport sets for [Cleared.broadcast]. */
    fun datagramSend(to: Cleared, send: DatagramSend, io: IoScope)
    fun certificate(to: Cleared, port: Int, sni: String?, timeoutMs: Int, io: IoScope): CertChain
}
