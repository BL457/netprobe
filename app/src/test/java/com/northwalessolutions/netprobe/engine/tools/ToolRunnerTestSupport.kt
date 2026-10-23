package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.ContinuationInterceptor

object ToolRunnerTestSupport {
    fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    val home = NetworkEnv(ip("10.0.3.50"), 24, ip("10.0.3.1"), emptyList(), "Home", null, Transport.WIFI)
    val office = NetworkEnv(ip("10.0.3.50"), 24, ip("10.0.9.1"), emptyList(), "Office", null, Transport.WIFI)
    fun target(hostId: Long) = ToolTarget(hostId, ip("10.0.3.9"), 5000, tls = false, serverName = null)

    open class FakeStore : ToolStore {
        override val ready = CompletableDeferred<Unit>().also { it.complete(Unit) }
        private var nextId = 0L
        val started = mutableListOf<String>()
        val finished = mutableListOf<Triple<Long, ToolRunStatus, String>>()
        val finishedAt = mutableListOf<Long?>()
        val findings = mutableListOf<Pair<Long, String>>()
        val extras = mutableListOf<String?>()
        val hostIds = mutableListOf<Long?>()
        val toolNames = mutableListOf<String>()
        override suspend fun startRun(hostId: Long?, toolId: String, port: Int, startedAt: Long, optionsJson: String): Long { started += optionsJson; hostIds += hostId; return ++nextId }
        override suspend fun startRun(hostId: Long?, toolId: String, port: Int, startedAt: Long, optionsJson: String, route: String?, toolName: String): Long {
            toolNames += toolName
            return startRun(hostId, toolId, port, startedAt, optionsJson)
        }
        override suspend fun finish(runId: Long, finishedAt: Long?, status: ToolRunStatus, summary: String) { finished += Triple(runId, status, summary); this.finishedAt += finishedAt }
        override suspend fun addFinding(runId: Long, kind: String, value: String, detail: String?, extra: String?): Long { findings += runId to value; extras += extra; return findings.size.toLong() }
        override suspend fun findingCount(runId: Long) = findings.count { it.first == runId }
        override suspend fun markInterrupted(summaryOf: (Int) -> String) {}
        val optionWrites = mutableListOf<String>()
        override suspend fun setOptions(runId: Long, json: String) { optionWrites += json }
    }
    /** A module with no inputs whose display name is its key; [targetKind] lets a test register a tool that needs no device. */
    class FakeTool(val key: String, val targetKind: TargetKind = TargetKind.PORT, val body: suspend (RunControl, ToolProgress) -> ToolResult) : ToolModule {
        override val descriptor = ToolDescriptor(key, key, "", ToolIcon.TERMINAL, ToolCategory.WEB, targetKind, emptyList())
        var lastTarget: ToolTarget? = null
        override fun applies(port: Int, service: ServiceInfo) = true
        override suspend fun run(target: ToolTarget?, inputs: ToolInputs, control: RunControl, progress: ToolProgress): ToolResult {
            lastTarget = target
            return body(control, progress)
        }
    }
    /** The default clock is the test scheduler's virtual time, never a constant: each run's RateBucket reads it, so a frozen clock would never refill and a gate after a resume (or past the burst) would hang. */
    @OptIn(ExperimentalCoroutinesApi::class)
    class Rig(scope: CoroutineScope, tool: ToolModule, val store: FakeStore = FakeStore(), val busy: HostBusyRegistry = HostBusyRegistry(),
              val messages: AppMessages = AppMessages(),
              now: () -> Long = (scope.coroutineContext[ContinuationInterceptor] as TestDispatcher).scheduler.let { s -> { s.currentTime } },
              bind: suspend (Inet4Address, NetworkEnv, suspend () -> Unit) -> Unit = { _, _, block -> block() }) {
        val env = MutableStateFlow<NetworkEnv?>(home)
        val foreground = MutableStateFlow(true)
        val finishedHosts = mutableListOf<Long>()
        val runner = ToolRunner(scope, scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher, ToolRegistry(listOf(tool)), store, busy,
            messages, env, foreground, bind, onFinished = { finishedHosts += it }, clock = now)
    }
}
