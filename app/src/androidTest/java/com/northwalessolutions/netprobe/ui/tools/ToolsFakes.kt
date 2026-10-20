package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.wordlists.WordlistMeta

object ToolsFakes {
    fun chip(tool: ToolId, port: Int) = ToolChip(tool, port, "$port", ToolCopy.chipDescription(ToolCopy.toolName(tool), "$port"))
    fun tools(hasRun: Boolean = true) = ToolsUiState(2, "Last run 2 hours ago: Directory brute-force on 5000, 7 found in 4614 paths", listOf(
        ToolBlock(ToolId.DIR_BRUTEFORCE, ToolCopy.DIR_BRUTEFORCE, hasRun, "Last run 2 hours ago on 5000: 7 found in 4614 paths", listOf(chip(ToolId.DIR_BRUTEFORCE, 5000), chip(ToolId.DIR_BRUTEFORCE, 5001))),
        ToolBlock(ToolId.HTTP_FINGERPRINT, ToolCopy.HTTP_FINGERPRINT, false, ToolCopy.NOT_RUN_YET, listOf(chip(ToolId.HTTP_FINGERPRINT, 5000)))))
    val lists = listOf(WordlistMeta("builtin:common-short", "common-short", 298, null, true), WordlistMeta("builtin:admin-panels", "admin-panels", 153, null, true),
        WordlistMeta("builtin:api-paths", "api-paths", 137, null, true))
    fun sheet(busyReason: String? = null) = DirBruteSheetState(5000, "http://10.0.3.10:5000/", lists, DirBruteOptions(), busyReason)
    fun results(findings: List<FindingRow> = listOf(FindingRow(1, "/admin", "200  1.2 KB", "http://10.0.3.10:5000/admin"), FindingRow(2, "Pi-hole", "Page title", null)),
                index: Int = 1, count: Int = 3, active: Boolean = false) =
        ToolResultsState(9, "Directory brute-force  10.0.3.10:5000", if (active) "Running, 12 of 300, 2 found  3 s" else "Done, 2 hours ago", "7 found in 300 paths", index, count, findings, active)
}
