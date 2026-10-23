package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.Finding
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.RunControl
import com.northwalessolutions.netprobe.engine.tools.ToolProgress
import com.northwalessolutions.netprobe.engine.tools.WordlistRef
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A scripted transport: records every call and answers from handlers. It never touches the network. */
class FakeScriptTransport : ScriptTransport {
    val calls = CopyOnWriteArrayList<String>()
    val clearedAddresses = CopyOnWriteArrayList<InetAddress>()
    @Volatile var httpHandler: (Cleared, HttpCall) -> HttpReply = { _, _ ->
        HttpReply(200, "OK", listOf("Server" to "fake", "Set-Cookie" to "a=1", "Set-Cookie" to "b=2"), "hello".toByteArray(), false, 3)
    }
    @Volatile var exchangeHandler: (Cleared, StreamCall) -> StreamReply = { _, c -> StreamReply("SSH-2.0-fake\r\n".toByteArray(), false, 2).also { check(c.port > 0) } }
    @Volatile var udpHandler: (Cleared, DatagramCall) -> DatagramReply = { _, _ -> DatagramReply(byteArrayOf(1, 2, 3), 4) }
    @Volatile var resolveHandler: (ClearedName) -> List<InetAddress> = { listOf(InetAddress.getByAddress(it.name, byteArrayOf(10, 0, 0, 9))) }
    @Volatile var reverseHandler: (Cleared) -> String? = { "host.example" }
    @Volatile var certHandler: (Cleared) -> CertChain = {
        CertChain("TLSv1.3", "TLS_AES_128_GCM_SHA256", listOf(CertFacts("CN=fake", "CN=ca", listOf("fake.example"), 1000, 2000, "RSA", 2048, "SHA256withRSA", "01", "ab", "cd", false)))
    }
    val connections = CopyOnWriteArrayList<FakeConnection>()

    inner class FakeConnection(val to: Cleared, val call: StreamCall) : StreamConnection {
        val sent = CopyOnWriteArrayList<ByteArray>()
        @Volatile var closed = false
        @Volatile var recvHandler: () -> StreamReply = { StreamReply("220 hello\r\n".toByteArray(), false, 1) }
        override fun send(bytes: ByteArray) { sent += bytes }
        override fun recv(maxBytes: Int, timeoutMs: Int, readUntil: ByteArray?) = recvHandler()
        override fun close() { closed = true }
    }

    private fun note(s: String, to: Cleared? = null) { calls += s; to?.let { clearedAddresses += it.address } }

    override fun resolve(name: ClearedName, io: IoScope): List<InetAddress> { note("resolve ${name.name}"); io.ensureAlive(); return resolveHandler(name) }
    override fun reverse(to: Cleared, io: IoScope): String? { note("reverse ${to.address.hostAddress}", to); return reverseHandler(to) }
    override fun http(to: Cleared, call: HttpCall, io: IoScope): HttpReply {
        note("http ${call.method} ${if (call.tls) "https" else "http"}://${call.hostHeader}${call.pathAndQuery} -> ${to.address.hostAddress}:${call.port}", to)
        io.ensureAlive()
        return httpHandler(to, call)
    }
    override fun exchange(to: Cleared, call: StreamCall, io: IoScope): StreamReply { note("tcp ${to.address.hostAddress}:${call.port}", to); return exchangeHandler(to, call) }
    override fun connect(to: Cleared, call: StreamCall, io: IoScope): StreamConnection {
        note("connect ${to.address.hostAddress}:${call.port}", to)
        return FakeConnection(to, call).also { connections += it }
    }
    override fun datagram(to: Cleared, call: DatagramCall, io: IoScope): DatagramReply { note("udp ${to.address.hostAddress}:${call.port}", to); return udpHandler(to, call) }
    /** Every ctx.udp.send: where it went and whether the guard marked the address as a broadcast. */
    class SentDatagram(val address: InetAddress, val port: Int, val data: ByteArray, val broadcast: Boolean)
    val sentDatagrams = CopyOnWriteArrayList<SentDatagram>()
    override fun datagramSend(to: Cleared, send: DatagramSend, io: IoScope) {
        note("udpsend ${to.address.hostAddress}:${send.port}", to)
        sentDatagrams += SentDatagram(to.address, send.port, send.data, to.broadcast)
    }
    override fun certificate(to: Cleared, port: Int, sni: String?, timeoutMs: Int, io: IoScope): CertChain { note("cert ${to.address.hostAddress}:$port sni=$sni", to); return certHandler(to) }
}

/** Approves everything, resolving nothing: names map to 10.0.0.9, literals to themselves. For testing the host API apart from the scope. */
object AllowAllGate : ScopeGate {
    private fun address(host: String): InetAddress {
        val parts = host.split('.')
        return if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) InetAddress.getByAddress(host, ByteArray(4) { parts[it].toInt().toByte() })
        else InetAddress.getByAddress(host, byteArrayOf(10, 0, 0, 9))
    }
    override fun clearConnect(host: String, port: Int) = Cleared(address(host), host)
    override fun clearName(name: String) = ClearedName(name)
    override fun clearReverse(ip: String) = Cleared(address(ip), ip)
}

class FakeControl : RunControl {
    val gates = AtomicInteger(0)
    @Volatile var onGate: suspend () -> Unit = {}
    override suspend fun gate() { gates.incrementAndGet(); onGate() }
    override val pauseEpoch: Int = 0
    override fun excused(startEpoch: Int): Boolean = false
}

class RecordingProgress : ToolProgress {
    val findings = CopyOnWriteArrayList<Finding>()
    val updates = CopyOnWriteArrayList<List<Any?>>()
    override fun update(done: Int, total: Int?, found: Int, errors: Int) { updates += listOf(done, total, found, errors) }
    override suspend fun finding(f: Finding) { findings += f }
}

class Harness(
    val limits: ScriptLimits = ScriptLimits(findingsPerSecond = 1_000_000, findingsBurst = 1_000_000),
    val transport: FakeScriptTransport = FakeScriptTransport(),
    val gate: ScopeGate = AllowAllGate,
    val control: FakeControl = FakeControl(),
    val progress: RecordingProgress = RecordingProgress(),
    val memory: MemoryProbe = MemoryProbe { 0.1 },
    val wordlist: suspend (WordlistRef, Int, Int) -> List<String> = { _, _, _ -> emptyList() },
    val network: NetworkScope = NetworkScope.TARGET,
    val devices: suspend () -> List<ScriptDevice> = { emptyList() },
) {
    val engine = RhinoScriptEngine(limits, memory)
    fun host() = ScriptHost(transport, gate, control, progress, wordlist, network, devices)
    fun run(source: String, inputs: Map<String, Any?> = emptyMap()): ScriptRunResult = runBlocking { engine.run(ScriptRunRequest(source, inputs, host())) }
    /** Wraps a body in a tool object, runs it and returns the result. */
    fun body(js: String, inputs: Map<String, Any?> = emptyMap()): ScriptRunResult =
        run("tool = { id: 'test-tool', name: 'T', run: function (inputs, ctx) {\n$js\n} };", inputs)
    fun evaluate(source: String): ScriptEvaluation = runBlocking { engine.evaluate(source) }
}

/** The summary of a run that the script finished with `return "..."`-style values: the body returns {status:'done', summary:<value>}. */
fun Harness.value(expr: String, inputs: Map<String, Any?> = emptyMap()): String {
    val r = body("var __v; try { __v = String($expr); } catch (e) { __v = 'THROWN:' + e.message; } return { status: 'done', summary: __v };", inputs)
    check(r.status == ToolRunStatus.DONE) { "run failed: ${r.summary}" }
    return r.summary
}
