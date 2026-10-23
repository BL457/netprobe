package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolFindingEntity
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ExportFinding(val value: String, val detail: String?, val url: String?)
data class ExportRun(val toolName: String, val target: String, val nameSent: String?, val startedAt: Long, val finishedAt: Long?, val statusLabel: String,
                     val summary: String, val optionsLine: String?, val findings: List<ExportFinding>, val zone: TimeZone)

/** The plain-text share of one run (spec 7.5). Hints never reach here: the DAO's findings query excludes them. */
object ToolExport {
    private const val TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
    private const val SNMP_PORT = 161
    /** The target line; [ip] is null for a run with no device. An unknown key (a removed script tool) is shown as the bare address. */
    fun target(toolKey: String, ip: String?, port: Int, tls: Boolean): String {
        if (ip == null) return ToolCopy.NO_DEVICE
        return when (toolKey) {
            ToolKeys.DIR_BRUTEFORCE, ToolKeys.HTTP_FINGERPRINT -> "${if (tls) "https" else "http"}://$ip:$port/"
            ToolKeys.TLS_CERTIFICATE -> "$ip:$port"
            ToolKeys.SMB_SESSION -> "smb://$ip/"
            ToolKeys.SNMP_WALK -> "udp://$ip:$SNMP_PORT"
            ToolKeys.IDENTIFY -> "$ip:$port"
            else -> if (port == 0) ip else "$ip:$port"
        }
    }
    fun subject(toolName: String, ip: String?, port: Int): String = ToolCopy.exportSubject(toolName, ip, port)
    /** [toolName] is the registry name when the tool is still installed, else the run's stored name; [ip] is null for a run with no device. */
    fun of(run: ToolRunEntity, ip: String?, findings: List<ToolFindingEntity>, zone: TimeZone = TimeZone.getDefault(), toolName: String? = null): ExportRun {
        val o = ToolRunOptions.parse(run.optionsJson)
        return ExportRun(toolName ?: run.toolName.ifEmpty { ToolCopy.toolName(run.toolId) }, target(run.toolId, ip, run.port, o.tls), o.serverName, run.startedAt,
            run.finishedAt, ToolCopy.statusLabel(run.status), run.summary, o.optionsLine(), findings.map { ExportFinding(it.value, it.detail, FindingExtra.decode(it.extra)["url"]) }, zone)
    }
    fun text(e: ExportRun): String {
        val fmt = SimpleDateFormat(TIME_PATTERN, Locale.ROOT).apply { timeZone = e.zone }
        val lines = buildList {
            add(ToolCopy.exportTitle(e.toolName))
            add(ToolCopy.exportTarget(e.target))
            e.nameSent?.let { add(ToolCopy.exportNameSent(it)) }
            add(ToolCopy.exportStarted(fmt.format(Date(e.startedAt))))
            add(ToolCopy.exportFinished(e.finishedAt?.let { fmt.format(Date(it)) }))
            add(ToolCopy.exportStatus(e.statusLabel))
            add(ToolCopy.exportSummary(e.summary))
            e.optionsLine?.let { add(ToolCopy.exportOptions(it)) }
            add("")
            e.findings.forEach { add(listOf(it.value, it.detail.orEmpty(), it.url.orEmpty()).joinToString("\t")) }
        }
        return lines.joinToString("\n")
    }
}
