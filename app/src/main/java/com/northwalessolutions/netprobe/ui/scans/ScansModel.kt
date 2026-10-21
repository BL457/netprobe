package com.northwalessolutions.netprobe.ui.scans

import com.northwalessolutions.netprobe.engine.tools.ToolId

enum class ScansSource { RADAR, QUICK_SCAN_ALL, PORT_SCAN, TOOL, WORDLIST_IMPORT }

/** There is no paused state: every pause is a background pause, so it is never on screen (spec 3.1). */
enum class ScansState { RUNNING, DONE, STOPPED, FAILED, INTERRUPTED }

/**
 * One row of the Scans screen. [key] is unique and stable ("live:<LiveWork id>" or "tool:<runId>"). [title] and [detail] are the two text
 * lines, built by ScansRules so the wording is testable: for a running row [detail] is the live progress, for a finished row it is
 * "summary, when". [liveId] is the LiveWork id of a running item, the handle its Stop uses.
 */
data class ScansItem(
    val key: String, val source: ScansSource, val liveId: Long?, val networkId: Long?, val hostId: Long?, val host: String?,
    val tool: ToolId?, val port: Int?, val state: ScansState, val done: Int?, val total: Int?, val found: Int?,
    val startedAt: Long, val durationMs: Long?, val endedAt: Long?, val summary: String?, val runId: Long?,
    val title: String, val detail: String,
) {
    val running: Boolean get() = state == ScansState.RUNNING
    /** The time the window, the clear cut-off and the sort use: the end, or the start of an item that has no end (Interrupted). */
    val sortTime: Long get() = endedAt ?: startedAt
}

data class ScansList(val running: List<ScansItem> = emptyList(), val recent: List<ScansItem> = emptyList())
