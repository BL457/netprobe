package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.runtime.Immutable
import com.northwalessolutions.netprobe.data.DisplayName
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.data.PortEntity
import com.northwalessolutions.netprobe.engine.tools.FindingKind
import com.northwalessolutions.netprobe.engine.tools.MacText
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.ui.home.RelativeTime
import com.northwalessolutions.netprobe.ui.manual.ManualCopy
import java.util.Locale

/** One discovered device in the picker. */
@Immutable data class DeviceChoice(val hostId: Long, val ip: String, val name: String, val detail: String, val mac: String = "")

/** The device picker's rules: the rows, the search over them, and the typed-address row. */
object DevicePick {
    /** Rows sorted by address (numerically), with the name the device list shows and its vendor as the detail. */
    fun rows(hosts: List<HostEntity>): List<DeviceChoice> = hosts.filter { !it.manualOnly }
        .sortedBy { Ipv4.parse(it.ip)?.let(SubnetMath::toInt)?.toLong()?.and(UNSIGNED) ?: Long.MAX_VALUE }
        .map { h ->
            val name = DisplayName.of(h)
            val vendor = listOfNotNull(h.userVendor, h.identVendor, h.vendor).firstOrNull { it.isNotBlank() }
            DeviceChoice(h.id, h.ip, name, listOfNotNull(h.ip.takeIf { name != h.ip }, vendor).joinToString(", "), MacText.normalize(h.mac))
        }

    /** Every word of [query] must appear in the name, address or detail (case-insensitive); a blank query lists everything. */
    fun search(rows: List<DeviceChoice>, query: String): List<DeviceChoice> {
        val words = query.trim().lowercase(Locale.ROOT).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return rows
        return rows.filter { r ->
            val hay = "${r.name} ${r.ip} ${r.detail}".lowercase(Locale.ROOT)
            words.all { it in hay }
        }
    }

    /** The address to offer as "Use <address>" when [query] is an IPv4 address no listed device has; null otherwise. */
    fun typedAddress(rows: List<DeviceChoice>, query: String): String? {
        val text = query.trim()
        val ip = Ipv4.parse(text) ?: return null
        return ip.hostAddress?.takeIf { address -> rows.none { it.ip == address } }
    }

    private const val UNSIGNED = 0xFFFFFFFFL
}

/** One open port of the chosen device in the picker. */
@Immutable data class PortChoice(val port: Int, val label: String)

object PortChoices {
    /** The device's open ports in port order, each with its service name (and product when known). */
    fun of(ports: List<PortEntity>): List<PortChoice> = ports.sortedBy { it.port }.map { p ->
        val service = listOfNotNull(p.serviceName.takeIf { it.isNotBlank() }, p.product?.takeIf { it.isNotBlank() }).joinToString(", ")
        PortChoice(p.port, ToolsUiCopy.portChoice(p.port, service))
    }
}

/** A finding's label: the script's own kind for an OTHER finding that carries one (ruling c). Built-in findings have no label. */
object FindingLabel {
    const val LOG = "log"
    fun of(kind: String, extra: Map<String, String>): String? =
        if (FindingKind.parse(kind) == FindingKind.OTHER) extra["kind"]?.trim()?.takeIf { it.isNotEmpty() } else null
    fun isLog(row: FindingRow): Boolean = row.label == LOG
}

/** A finding's page picture: the stored relative path, kept only when it has the exact shape the snapshot store writes. */
object FindingImage {
    private val PATH = Regex("[0-9]{1,18}/[0-9]{1,6}\\.jpg")
    fun of(extra: Map<String, String>): String? = extra["image"]?.takeIf { PATH.matches(it) }
}

/** The findings of one run split for the tool page: the findings, and the script log lines that only a failed run shows, collapsed. */
@Immutable data class RunFindings(val findings: List<FindingRow>, val log: List<FindingRow>, val showLog: Boolean)

object RunFindingsSplit {
    fun of(rows: List<FindingRow>, failed: Boolean): RunFindings {
        val log = rows.filter(FindingLabel::isLog)
        return RunFindings(rows.filterNot(FindingLabel::isLog), log, showLog = failed && log.isNotEmpty())
    }
}

/** A row of the page's "Previous runs". */
@Immutable data class PreviousRun(val runId: Long, val title: String, val line: String, val description: String)

object PreviousRuns {
    const val LIMIT = 20
    /** How many of the network's newest runs are read to find [LIMIT] of one tool. */
    const val SCAN = 200

    fun of(runs: List<NetworkRun>, toolKey: String, now: Long, formatDate: (Long) -> String = RelativeTime::defaultDate): List<PreviousRun> =
        runs.filter { it.run.toolId == toolKey }.take(LIMIT).map { nr ->
            val title = ToolsUiCopy.runTitle(nr.ip, nr.run.port)
            PreviousRun(nr.run.id, title, ManualCopy.historyLine(ToolsUiModel.summaryOf(nr.run), RelativeTime.of(nr.run.startedAt, now, formatDate)),
                ToolsUiCopy.runDescription(title))
        }
}
