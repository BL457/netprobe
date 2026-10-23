package com.northwalessolutions.netprobe.ui.manual

import com.northwalessolutions.netprobe.data.ManualRow
import com.northwalessolutions.netprobe.engine.discovery.KnownRanges
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.data.NetworkSignature
import com.northwalessolutions.netprobe.engine.tools.ManualRoute
import com.northwalessolutions.netprobe.engine.tools.RouteContext
import com.northwalessolutions.netprobe.engine.tools.ToolApplicability
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolInputs
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.engine.tools.ToolTarget
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.ui.home.ScanController
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolsUiModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.Inet4Address

/** One Run tap after validation (3C spec 4.3): [tls] already follows ManualTarget.tls, [options] the brute-force sheet when there was one. */
data class ManualRequest(val env: NetworkEnv, val ip: Inet4Address, val port: Int, val tool: String, val tls: Boolean, val inputs: ToolInputs = ToolInputs.NONE)
/** [hostId] is the row the run was keyed on, or null when the start ended before any row (no network, no binder). */
data class ManualResult(val start: ToolRunner.Start, val hostId: Long?)

enum class ManualStep { OPEN_SHEET, START }

object ManualFlow {
    /** Spec 4.3 step 3: brute-force opens its options sheet first and creates nothing until the sheet's Run; the other tools start at once. */
    fun firstStep(tool: String): ManualStep = if (tool == ToolKeys.DIR_BRUTEFORCE) ManualStep.OPEN_SHEET else ManualStep.START
}

/** Spec 4.3 step 6: the 3B messages in the screen's snackbar; null when the run started. */
object ManualMessages {
    fun of(result: ManualResult, busy: Map<Long, HostBusyRegistry.Work>): String? = when (result.start) {
        is ToolRunner.Start.Started -> null
        ToolRunner.Start.HostBusy -> ToolsUiModel.busyMessage(result.hostId?.let { busy[it] } ?: HostBusyRegistry.Work.TOOL)
        ToolRunner.Start.TooMany -> ToolCopy.BUSY_TOO_MANY
        ToolRunner.Start.NoNetwork -> ScanController.NO_NETWORK
    }
}

/**
 * The manual start path (3C spec 4.3, 4.5, G8). Every dependency is a function so the JVM tests drive it with fakes; ManualToolsViewModel
 * passes the container's undo store, network repository, settings, binder lookup, host repository and tool runner.
 */
class ManualStarter(
    private val committedBefore: suspend (suspend () -> ManualResult) -> ManualResult,
    /** The network row for [NetworkEnv], or null when there is none or its Forget is pending. */
    private val network: suspend (NetworkEnv) -> Long?,
    private val known: suspend (Long, NetworkEnv) -> KnownRanges,
    private val hasBinder: (NetworkEnv) -> Boolean,
    /** NetworkEnvMonitor.routeContext: the LAN's internet and the default network, for Director ruling R4. */
    private val routeContext: (NetworkEnv) -> RouteContext,
    /** The env now, read just before the runner starts (plan review G15). */
    private val envNow: () -> NetworkEnv?,
    private val target: suspend (Long, String) -> ManualRow,
    /** Deletes the row only while it is still manualOnly with no run (HostRepository.discardManual). */
    private val discard: suspend (Long) -> Unit,
    private val runnerStart: suspend (ToolTarget, String, ToolInputs) -> ToolRunner.Start,
) {
    suspend fun startManual(r: ManualRequest): ManualResult = committedBefore { begin(r) }

    private suspend fun begin(r: ManualRequest): ManualResult {
        val networkId = network(r.env) ?: return ManualResult(ToolRunner.Start.NoNetwork, null)
        val route = ManualRoute.of(r.ip, known(networkId, r.env), r.env, routeContext(r.env))
        // Spec 4.5: a known-range target with no Wi-Fi or Ethernet Network to bind is refused here, before any row, never run unbound.
        if (ManualRoute.needsBinder(route) && !hasBinder(r.env)) return ManualResult(ToolRunner.Start.NoNetwork, null)
        val row = target(networkId, r.ip.hostAddress)
        // Brute-force always sends the IP (3B 4.9); the other tools send the row's known name, which a fresh manual row does not have.
        val name = if (r.tool == ToolKeys.DIR_BRUTEFORCE) null else ToolApplicability.serverName(row.host)
        // Plan review G15: a route chosen for the tap's network must never be bound on another; the runner reads its own env at start.
        if (envNow()?.let { NetworkSignature.identityKey(it) } != NetworkSignature.identityKey(r.env)) {
            if (row.inserted) discard(row.host.id)
            return ManualResult(ToolRunner.Start.NoNetwork, row.host.id)
        }
        val started = try {
            runnerStart(ToolTarget(row.host.id, r.ip, r.port, r.tls, name, route), r.tool, r.inputs)
        } catch (t: Throwable) {
            if (row.inserted) withContext(NonCancellable) { discard(row.host.id) }
            throw t
        }
        if (started !is ToolRunner.Start.Started && row.inserted) discard(row.host.id)
        return ManualResult(started, row.host.id)
    }
}
