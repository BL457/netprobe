package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolFindingEntity
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.engine.tools.ToolId
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
    fun target(toolId: ToolId, ip: String, port: Int, tls: Boolean): String {
        return when (toolId) {
            ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT -> "${if (tls) "https" else "http"}://$ip:$port/"
            ToolId.TLS_CERTIFICATE -> "$ip:$port"
            ToolId.SMB_SESSION -> "smb://$ip/"
            ToolId.SNMP_WALK -> "udp://$ip:$SNMP_PORT"
        }
    }
    fun subject(toolName: String, ip: String, port: Int): String = ToolCopy.exportSubject(toolName, ip, port)
    fun of(run: ToolRunEntity, ip: String, findings: List<ToolFindingEntity>, zone: TimeZone = TimeZone.getDefault()): ExportRun {
        val tool = ToolId.byKey(run.toolId) ?: ToolId.DIR_BRUTEFORCE
        val o = ToolRunOptions.parse(run.optionsJson)
        return ExportRun(ToolCopy.toolName(tool), target(tool, ip, run.port, o.tls), o.serverName, run.startedAt, run.finishedAt, ToolCopy.statusLabel(run.status),
            run.summary, o.optionsLine(), findings.map { ExportFinding(it.value, it.detail, FindingExtra.decode(it.extra)["url"]) }, zone)
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
