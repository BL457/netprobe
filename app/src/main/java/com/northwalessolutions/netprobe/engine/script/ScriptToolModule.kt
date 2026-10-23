package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.common.currentBinder
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.Finding
import com.northwalessolutions.netprobe.engine.tools.FindingKind
import com.northwalessolutions.netprobe.engine.tools.InputsResult
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.RunControl
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolDescriptor
import com.northwalessolutions.netprobe.engine.tools.ToolInputs
import com.northwalessolutions.netprobe.engine.tools.ToolModule
import com.northwalessolutions.netprobe.engine.tools.ToolProgress
import com.northwalessolutions.netprobe.engine.tools.ToolResult
import com.northwalessolutions.netprobe.engine.tools.ToolTarget
import com.northwalessolutions.netprobe.engine.tools.WordlistRef
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolModelCopy
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress

/**
 * A tool written in JavaScript (spec D2, D4, D6). The descriptor was read from the script already; each run evaluates the source again in a
 * fresh sandbox and calls `tool.run`. The module works out the run's network scope here: the target (if any), the current subnet (LAN), and
 * the route sockets leave on. The binding is the one a manual tool run already has, or, for a run with no device, the Wi-Fi or Ethernet
 * network (the same rule ManualRoute uses for addresses inside the known ranges). Scripts never run on the caller's thread.
 */
class ScriptToolModule(
    override val descriptor: ToolDescriptor,
    val source: String,
    private val engine: ScriptEngine,
    private val env: () -> NetworkEnv?,
    private val lanBinder: (NetworkEnv) -> SocketBinder?,
    private val wordlistLines: suspend (WordlistRef, Int, Int) -> List<String> = { _, _, _ -> emptyList() },
    private val transportFor: (SocketBinder?, (InetAddress) -> Boolean) -> ScriptTransport = { binder, lan -> SocketScriptTransport(binder, lan) },
) : ToolModule {
    /** A tool with a device or a port is offered on the device page like a built-in (ruling a of the B3 hand-over); a tool with no target is not. */
    override fun applies(port: Int, service: ServiceInfo): Boolean = descriptor.target != TargetKind.NONE

    /**
     * Whether the device page offers the tool for [host]: TARGET and ANY scopes always (the device is the target, or anything may be reached);
     * LAN only while the host's address is inside the current network's subnet.
     */
    override fun appliesToHost(host: HostEntity): Boolean {
        if (descriptor.target == TargetKind.NONE) return false
        if (descriptor.network != NetworkScope.LAN) return true
        val network = env() ?: return false
        val address = Ipv4.parse(host.ip) ?: return false
        return SubnetMath.of(network.interfaceIp, network.prefixLength).contains(address)
    }

    /** Re-validates the typed inputs against the descriptor: types, ranges, options, required (the same rules the form shows). */
    override fun check(inputs: ToolInputs): Map<String, String> =
        (ToolInputs.validate(descriptor, toRaw(inputs)) as? InputsResult.Invalid)?.errors ?: emptyMap()

    /** The per-run confirmation belongs to the app, not to the run's record: it is left out of optionsJson. */
    override fun recorded(inputs: ToolInputs): ToolInputs = ToolInputs(inputs.values.filterKeys { it != CONFIRM_ANY })

    override suspend fun run(target: ToolTarget?, inputs: ToolInputs, control: RunControl, progress: ToolProgress): ToolResult {
        if (descriptor.target != TargetKind.NONE && target == null) return failed(ToolModelCopy.NO_TARGET)
        // ANY is confirmed for every run (G3). The form asks; a run started without the answer does not start.
        if (descriptor.network == NetworkScope.ANY && inputs.values[CONFIRM_ANY] != true) return failed(ScriptCopy.CONFIRM_REQUIRED)

        val raw = toRaw(inputs).toMutableMap()
        if (target != null) {
            raw.putIfAbsent(ToolInputs.IP, target.ip.hostAddress.orEmpty())
            if (descriptor.target == TargetKind.HOST && target.mac.isNotEmpty()) raw.putIfAbsent(ToolInputs.MAC, target.mac)
            if (descriptor.target == TargetKind.PORT) { raw.putIfAbsent(ToolInputs.PORT, target.port.toString()); raw.putIfAbsent(ToolInputs.TLS, target.tls.toString()) }
        }
        val known = inputs.values.values.filterIsInstance<WordlistRef>().associateBy { it.id }
        val valid = when (val checked = ToolInputs.validate(descriptor, raw) { id -> known[id] }) {
            is InputsResult.Valid -> checked.inputs
            is InputsResult.Invalid -> return failed(ScriptCopy.inputsInvalid(checked.errors.entries.first().let { "${it.key.take(INPUT_ECHO)} ${it.value}" }))
        }

        val network = env()
        val lan = network?.let { ScopeLan(it.interfaceIp, it.prefixLength) }
        if (descriptor.network == NetworkScope.LAN && lan == null) return failed(ScriptCopy.NO_LAN)
        val subnet = lan?.let { SubnetMath.of(it.phoneIp, it.prefixLength) }
        val binder = currentBinder() ?: network?.let(lanBinder)
        val transport = transportFor(binder) { a -> a is Inet4Address && subnet?.contains(a) == true }
        val job = currentCoroutineContext()[Job]
        val resolveIo = object : IoScope {
            override fun track(c: Closeable) {}
            override fun untrack(c: Closeable) {}
            override fun ensureAlive() { if (job != null && !job.isActive) throw ScriptAbort(AbortReason.CANCELLED, ScriptCopy.abortSummary(AbortReason.CANCELLED)) }
        }
        val guard = NetworkScopeGuard(descriptor.network, target?.let { ScopeTarget(it.ip, it.serverName) }, lan, descriptor.broadcast) { name -> transport.resolve(name, resolveIo) }

        val scriptInputs = LinkedHashMap(valid.values.filterKeys { it != CONFIRM_ANY })
        // A host tool always gets inputs.mac: what the user typed into its own "mac" input, else the device's MAC, else "".
        if (descriptor.target == TargetKind.HOST) {
            val typed = (valid.values[ToolInputs.MAC] as? String)?.takeIf { it.isNotBlank() }
            scriptInputs[ToolInputs.MAC] = typed ?: target?.mac.orEmpty()
        }
        // A broadcast tool is told the address to send to: the script cannot work out the prefix length itself.
        if (descriptor.broadcast) scriptInputs[ToolInputs.BROADCAST] = lan?.directedBroadcast()?.hostAddress.orEmpty()
        val result = engine.run(ScriptRunRequest(source, scriptInputs, ScriptHost(transport, guard, control, progress, wordlistLines)))
        return when (result.status) {
            ToolRunStatus.DONE -> ToolResult(ToolRunStatus.DONE, result.summary)
            else -> {
                // The log is kept only when a run fails (spec section 4): the last lines, as findings of kind "log".
                for (line in result.log.takeLast(LOG_LINES_KEPT)) progress.finding(Finding(FindingKind.OTHER, line, null, null, mapOf("kind" to "log")))
                ToolResult(ToolRunStatus.FAILED, ToolCopy.failed(result.summary))
            }
        }
    }

    private fun failed(reason: String) = ToolResult(ToolRunStatus.FAILED, ToolCopy.failed(reason))

    /** The typed values back as the form's raw strings, which is what ToolInputs.validate reads. */
    private fun toRaw(inputs: ToolInputs): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in inputs.values) {
            if (k == CONFIRM_ANY) continue
            out[k] = when (v) {
                null -> continue
                is Inet4Address -> v.hostAddress.orEmpty()
                is WordlistRef -> v.id
                is List<*> -> v.joinToString(",")
                else -> v.toString()
            }
        }
        return out
    }

    companion object {
        /** Set to true in the inputs by the UI once the user has confirmed a network "any" tool for this run (G3). */
        const val CONFIRM_ANY = ScriptDescriptorParser.CONFIRM_ANY
        private const val LOG_LINES_KEPT = 20
        private const val INPUT_ECHO = 40
    }
}
