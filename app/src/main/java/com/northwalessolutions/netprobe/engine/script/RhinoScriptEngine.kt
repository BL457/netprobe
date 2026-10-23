package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.RunControl
import com.northwalessolutions.netprobe.engine.tools.ToolProgress
import com.northwalessolutions.netprobe.engine.tools.Finding
import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mozilla Rhino behind [ScriptEngine] (spec D1 to D3, rulings G1, G4, G5). Each evaluation and each run gets its own thread, never the
 * main thread and never the shared tool dispatcher, its own Rhino Context and a fresh scope, so nothing a script does outlives it. A
 * watchdog thread abandons a run that outlives its wall clock, stops responding, or pushes the heap too far, even when the script is
 * inside a native call; the abandoned thread keeps no way to reach the app (its sockets are closed and every host call refuses).
 */
class RhinoScriptEngine(
    private val limits: ScriptLimits = ScriptLimits.DEFAULT,
    private val memory: MemoryProbe = MemoryProbe.RUNTIME,
) : ScriptEngine {
    private val factory = Sandbox.Factory()
    private val liveThreads = AtomicInteger(0)

    private sealed interface Outcome<out T> {
        class Done<T>(val value: T) : Outcome<T>
        class Failed(val error: Throwable) : Outcome<Nothing>
        class Tripped(val reason: AbortReason) : Outcome<Nothing>
    }

    // evaluate

    override suspend fun evaluate(source: String): ScriptEvaluation {
        if (source.toByteArray(Charsets.UTF_8).size > ScriptCaps.MAX_SOURCE_BYTES) return ScriptEvaluation.Failed(ScriptCopy.SOURCE_TOO_LARGE)
        val evalLimits = limits.copy(wallMs = limits.evalWallMs, stallMs = minOf(limits.stallMs, limits.evalWallMs))
        val job = Job(currentCoroutineContext()[Job])
        val session = RunSession(evalLimits, job, INERT_HOST, emptyMap(), memory = memory)
        try {
            return when (val out = onScriptThread(session, evalLimits) { evaluateOnThread(session, source) }) {
                is Outcome.Done -> out.value
                is Outcome.Tripped -> ScriptEvaluation.Failed(ScriptCopy.evaluationAborted(ScriptCopy.abortSummary(out.reason)))
                is Outcome.Failed -> ScriptEvaluation.Failed(describe(out.error).let { if (out.error is ScriptAbort) ScriptCopy.evaluationAborted(it) else it })
            }
        } finally { job.cancel() }
    }

    private fun evaluateOnThread(session: RunSession, source: String): ScriptEvaluation {
        val cx = factory.enterContext()
        try {
            cx.putThreadLocal(Sandbox.SESSION_KEY, session)
            val scope = Sandbox.newScope(cx)
            cx.evaluateString(scope, source, SCRIPT_NAME, 1, null)
            val tool = ScriptableObject.getProperty(scope, "tool")
            if (tool !is Scriptable || tool === Undefined.instance || tool is Function) return ScriptEvaluation.Failed(ScriptCopy.NO_TOOL_OBJECT)
            @Suppress("UNCHECKED_CAST")
            val plain = JsBridge.fromJs(tool, skipFunctions = true) as Map<String, Any?>
            val hasRun = plain["run"] === JsBridge.FUNCTION_MARKER
            return ScriptEvaluation.Loaded(plain.filterValues { it !== JsBridge.FUNCTION_MARKER }.mapValues { strip(it.value) }, hasRun)
        } finally { Context.exit() }
    }

    private fun strip(v: Any?): Any? = when (v) {
        JsBridge.FUNCTION_MARKER -> null
        is Map<*, *> -> v.filterValues { it !== JsBridge.FUNCTION_MARKER }.mapValues { strip(it.value) }
        is List<*> -> v.filter { it !== JsBridge.FUNCTION_MARKER }.map { strip(it) }
        else -> v
    }

    // run

    override suspend fun run(request: ScriptRunRequest): ScriptRunResult {
        if (request.source.toByteArray(Charsets.UTF_8).size > ScriptCaps.MAX_SOURCE_BYTES) return failed(ScriptCopy.SOURCE_TOO_LARGE)
        val job = Job(currentCoroutineContext()[Job])
        val session = RunSession(limits, job, request.host, request.inputs, memory = memory)
        try {
            return when (val out = onScriptThread(session, limits) { runOnThread(session, request.source) }) {
                is Outcome.Done -> out.value
                is Outcome.Tripped -> finish(session, ScriptRunResult(ToolRunStatus.FAILED, ScriptCopy.abortSummary(out.reason), session.logLines(), session.findings))
                is Outcome.Failed -> finish(session, ScriptRunResult(ToolRunStatus.FAILED, describe(out.error), session.logLines(), session.findings))
            }
        } finally {
            session.finish()
            job.cancel()
        }
    }

    private fun finish(session: RunSession, r: ScriptRunResult): ScriptRunResult { session.finish(); return r }
    private fun failed(message: String) = ScriptRunResult(ToolRunStatus.FAILED, message, emptyList(), 0)

    private fun runOnThread(session: RunSession, source: String): ScriptRunResult {
        val cx = factory.enterContext()
        try {
            cx.putThreadLocal(Sandbox.SESSION_KEY, session)
            val scope = Sandbox.newScope(cx)
            cx.evaluateString(scope, source, SCRIPT_NAME, 1, null)
            val tool = ScriptableObject.getProperty(scope, "tool")
            if (tool !is Scriptable || tool === Undefined.instance || tool is Function) return result(session, ToolRunStatus.FAILED, ScriptCopy.NO_TOOL_OBJECT)
            val run = ScriptableObject.getProperty(tool, "run") as? Function ?: return result(session, ToolRunStatus.FAILED, ScriptCopy.NO_RUN_FUNCTION)
            val inputs = JsBridge.toJs(cx, scope, session.inputs)
            val ctx = HostApi.build(cx, scope, session)
            val returned = run.call(cx, scope, tool, arrayOf(inputs, ctx))
            // A budget or a Stop that the script absorbed by polling ctx.cancelled() still ends the run as failed or stopped.
            session.reason?.let { return result(session, ToolRunStatus.FAILED, ScriptCopy.abortSummary(it)) }
            return interpret(session, returned)
        } catch (e: ScriptAbort) {
            return result(session, ToolRunStatus.FAILED, e.message ?: ScriptCopy.abortSummary(e.reason))
        } catch (e: RhinoException) {
            session.reason?.let { return result(session, ToolRunStatus.FAILED, ScriptCopy.abortSummary(it)) }
            return result(session, ToolRunStatus.FAILED, describe(e))
        } finally { Context.exit() }
    }

    private fun result(s: RunSession, status: ToolRunStatus, summary: String) = ScriptRunResult(status, summary, s.logLines(), s.findings)

    @Suppress("UNCHECKED_CAST")
    private fun interpret(session: RunSession, returned: Any?): ScriptRunResult {
        val default = ScriptCopy.findingsSummary(session.findings)
        if (returned == null || returned === Undefined.instance) return result(session, ToolRunStatus.DONE, default)
        val map = try { JsBridge.fromJs(returned) as? Map<String, Any?> } catch (e: ScriptHostError) { null }
            ?: return result(session, ToolRunStatus.DONE, default)
        val status = when (val st = map["status"]) {
            null, "done" -> ToolRunStatus.DONE
            "failed" -> ToolRunStatus.FAILED
            else -> return result(session, ToolRunStatus.FAILED, ScriptCopy.badStatus(st.toString().take(ECHO)))
        }
        val summary = (map["summary"] as? String)?.take(SUMMARY_MAX)?.takeIf { it.isNotBlank() } ?: default
        return result(session, status, summary)
    }

    private fun describe(t: Throwable): String = when (t) {
        is ScriptAbort -> t.message ?: ScriptCopy.abortSummary(t.reason)
        is RhinoException -> ScriptCopy.scriptError(t.details().take(SUMMARY_MAX), t.lineNumber())
        is OutOfMemoryError -> ScriptCopy.abortSummary(AbortReason.MEMORY)
        is StackOverflowError -> ScriptCopy.SCRIPT_TOO_DEEP
        else -> ScriptCopy.internalError(t.javaClass.simpleName)
    }

    // threads and the watchdog

    /**
     * Runs [body] on a fresh script thread and waits for it without blocking a thread of ours. The wait ends early when the watchdog
     * trips, and cancelling the caller stops the script: the session is aborted (closing every socket) and the thread is interrupted.
     */
    private suspend fun <T> onScriptThread(session: RunSession, lim: ScriptLimits, body: () -> T): Outcome<T> {
        if (liveThreads.get() >= ScriptCaps.MAX_SCRIPT_THREADS) return Outcome.Failed(IllegalStateException(ScriptCopy.TOO_MANY_SCRIPTS))
        val done = CompletableDeferred<Outcome<T>>()
        liveThreads.incrementAndGet()
        val thread = Thread(null, {
            try { done.complete(Outcome.Done(body())) }
            catch (t: Throwable) { done.complete(Outcome.Failed(t)) }
            finally { liveThreads.decrementAndGet() }
        }, "netprobe-script-${threadCount.incrementAndGet()}", ScriptCaps.SCRIPT_THREAD_STACK_BYTES)
        thread.isDaemon = true
        val watch: ScheduledFuture<*> = watchdog.scheduleWithFixedDelay({
            session.verdict()?.let { reason -> session.abort(reason); done.complete(Outcome.Tripped(reason)) }
        }, lim.watchdogTickMs, lim.watchdogTickMs, TimeUnit.MILLISECONDS)
        thread.start()
        try {
            return done.await()
        } catch (e: CancellationException) {
            session.abort(AbortReason.CANCELLED)
            thread.interrupt()
            throw e
        } finally {
            watch.cancel(false)
        }
    }

    private companion object {
        const val SCRIPT_NAME = "tool.js"
        const val SUMMARY_MAX = 300
        const val ECHO = 40
        val threadCount = AtomicInteger(0)
        val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "netprobe-script-watchdog").apply { isDaemon = true } }

        /** The host used while a descriptor is evaluated: there is no ctx, so nothing here is ever called. */
        val INERT_HOST = ScriptHost(DenyTransport, DenyAllGate, InertControl, InertProgress)
    }

    private object DenyTransport : ScriptTransport {
        private fun no(): Nothing = throw ScopeViolation(ScriptCopy.NO_NETWORK_IN_EVALUATION)
        override fun resolve(name: ClearedName, io: IoScope) = no()
        override fun reverse(to: Cleared, io: IoScope) = no()
        override fun http(to: Cleared, call: HttpCall, io: IoScope) = no()
        override fun exchange(to: Cleared, call: StreamCall, io: IoScope) = no()
        override fun connect(to: Cleared, call: StreamCall, io: IoScope) = no()
        override fun datagram(to: Cleared, call: DatagramCall, io: IoScope) = no()
        override fun datagramSend(to: Cleared, send: DatagramSend, io: IoScope) = no()
        override fun certificate(to: Cleared, port: Int, sni: String?, timeoutMs: Int, io: IoScope) = no()
    }
    private object InertControl : RunControl {
        override suspend fun gate() {}
        override val pauseEpoch: Int = 0
        override fun excused(startEpoch: Int): Boolean = false
    }
    private object InertProgress : ToolProgress {
        override fun update(done: Int, total: Int?, found: Int, errors: Int) {}
        override suspend fun finding(f: Finding) {}
    }
}
