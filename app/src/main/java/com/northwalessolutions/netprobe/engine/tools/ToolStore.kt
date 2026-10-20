package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import kotlinx.coroutines.Deferred

/** The slice of ToolRepository the runner needs, so the runner is fake-driven on the JVM (spec 12.1 "a fake repository"). */
interface ToolStore {
    val ready: Deferred<Unit>
    suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String): Long
    suspend fun finish(runId: Long, finishedAt: Long?, status: ToolRunStatus, summary: String)
    suspend fun addFinding(runId: Long, kind: String, value: String, detail: String?, extra: String?): Long
    suspend fun findingCount(runId: Long): Int
    suspend fun markInterrupted(summaryOf: (found: Int) -> String)
    suspend fun setOptions(runId: Long, json: String)
}
