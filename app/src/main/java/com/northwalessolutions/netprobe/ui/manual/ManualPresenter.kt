package com.northwalessolutions.netprobe.ui.manual

import com.northwalessolutions.netprobe.data.DisplayName
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.AppMessage
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.ui.home.RelativeTime
import com.northwalessolutions.netprobe.ui.tools.DirBruteSheetState
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolResultsState
import com.northwalessolutions.netprobe.ui.tools.ToolsUiModel

/** What the Director has typed and chosen; [httpsChoice] is null until he touches the switch, [attempted] true from the first Run tap. */
data class ManualForm(val ipText: String = "", val portText: String = "", val tool: ToolId = ToolId.HTTP_FINGERPRINT,
                      val httpsChoice: Boolean? = null, val attempted: Boolean = false)
/** A visible host of this network at the typed IP, with its stored services for the HTTPS default (spec 4.1 items 5 and 6). */
data class KnownTarget(val host: HostEntity, val services: Map<Int, ServiceInfo>)
data class ManualHistoryRow(val runId: Long, val title: String, val line: String, val description: String)
data class ManualUiState(
    val form: ManualForm = ManualForm(), val ipError: String? = null, val portError: String? = null,
    val portEnabled: Boolean = true, val showHttps: Boolean = true, val https: Boolean = false, val fixedLine: String? = null,
    val infoLine: String? = null, val lan: Boolean = true, val running: Boolean = false,
    val history: List<ManualHistoryRow> = emptyList(), val results: ToolResultsState? = null, val dirBrute: DirBruteSheetState? = null,
    val message: String? = null, val appMessage: AppMessage? = null,
)

/**
 * Pure state for the "Run a tool" screen (3C spec 4.1, 4.2); the view model only gathers its inputs. Per-tick progress is never part of it
 * (3B QA F4): the structural state sees [frozen] runs and the live text comes from [liveProgress] and [liveResultsStatus], read in draw.
 */
object ManualPresenter {
    const val RECENT_RUNS = 20

    /** A run with its per-tick counts zeroed: what the structural state may see (which run, its tool, whether its total is known). */
    fun frozen(a: ToolRunner.Active): ToolRunner.Active = a.copy(activeMs = 0, done = 0, found = 0, errors = 0)
    /** The pill's live progress text (3B ToolsUiModel.bar wording). */
    fun liveProgress(a: ToolRunner.Active?): String? = a?.let { ToolsUiModel.bar(it).progress }
    /** The results sheet's live status line for a running run (3B ToolCopy.resultsRunning wording). */
    fun liveResultsStatus(a: ToolRunner.Active?): String? = a?.let { ToolCopy.resultsRunning(ToolsUiModel.bar(it).progress) }

    fun present(form: ManualForm, home: Subnet?, lan: Boolean, known: KnownTarget?, history: List<NetworkRun>, active: ToolRunner.Active?,
                results: ToolResultsState?, dirBrute: DirBruteSheetState?, message: String?, appMessage: AppMessage?, now: Long,
                formatDate: (Long) -> String = RelativeTime::defaultDate): ManualUiState {
        val checked = ManualTarget.check(form.ipText, form.portText, form.tool, home, form.attempted)
        val fixed = ManualTarget.fixedPort(form.tool)
        val port = fixed ?: form.portText.trim().toIntOrNull()
        val webTool = form.tool == ToolId.DIR_BRUTEFORCE || form.tool == ToolId.HTTP_FINGERPRINT
        val https = form.httpsChoice ?: ManualTarget.httpsDefault(form.tool, port, port?.let { known?.services?.get(it) })
        val fixedLine = when (form.tool) {
            ToolId.SMB_SESSION -> ManualCopy.FIXED_SMB
            ToolId.SNMP_WALK -> ManualCopy.FIXED_SNMP
            ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT, ToolId.TLS_CERTIFICATE, ToolId.IDENTIFY -> null
            ToolId.IDENTIFY_ALL -> ManualCopy.FIXED_ALL
        }
        val info = if (!lan) ManualCopy.NO_LAN else known?.let { ManualCopy.knownDevice(DisplayName.of(it.host)) }
        val rows = history.mapNotNull { nr -> ToolId.byKey(nr.run.toolId)?.let { id -> historyRow(nr, id, now, formatDate) } }
        return ManualUiState(form, checked.ipError, checked.portError, portEnabled = fixed == null, showHttps = webTool, https = https, fixedLine = fixedLine,
            infoLine = info, lan = lan, running = active != null, history = rows,
            results = results, dirBrute = dirBrute, message = message, appMessage = appMessage)
    }

    private fun historyRow(nr: NetworkRun, id: ToolId, now: Long, formatDate: (Long) -> String): ManualHistoryRow {
        val name = ToolCopy.toolName(id)
        val label = ToolCopy.portLabel(nr.run.port, id == ToolId.SNMP_WALK)
        return ManualHistoryRow(nr.run.id, ManualCopy.historyRow(name, nr.ip, label),
            ManualCopy.historyLine(ToolsUiModel.summaryOf(nr.run), RelativeTime.of(nr.run.startedAt, now, formatDate)),
            ManualCopy.historyDescription(name, nr.ip, label))
    }
}
