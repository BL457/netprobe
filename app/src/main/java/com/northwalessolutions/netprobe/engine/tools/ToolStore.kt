package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import kotlinx.coroutines.Deferred

/** The slice of ToolRepository the runner needs, so the runner is fake-driven on the JVM (spec 12.1 "a fake repository"). */
interface ToolStore {
    val ready: Deferred<Unit>
    /** [hostId] is null for a tool that needs no device (schema 6). */
    suspend fun startRun(hostId: Long?, toolId: String, port: Int, startedAt: Long, optionsJson: String): Long
    /**
     * 3C spec 4.5: the run with its route stored ("WIFI", "ETHERNET", "OTHER", null), and [toolName], the tool's display name at the time
     * (schema 6). The default drops both, so a store that keeps neither (the JVM fakes) needs no change; ToolRepository overrides it and
     * writes them in the same insert.
     */
    suspend fun startRun(hostId: Long?, toolId: String, port: Int, startedAt: Long, optionsJson: String, route: String?, toolName: String): Long =
        startRun(hostId, toolId, port, startedAt, optionsJson)
    suspend fun finish(runId: Long, finishedAt: Long?, status: ToolRunStatus, summary: String)
    suspend fun addFinding(runId: Long, kind: String, value: String, detail: String?, extra: String?): Long
    suspend fun findingCount(runId: Long): Int
    suspend fun markInterrupted(summaryOf: (found: Int) -> String)
    suspend fun setOptions(runId: Long, json: String)
}
