package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.RunControl
import com.northwalessolutions.netprobe.engine.tools.ToolProgress
import com.northwalessolutions.netprobe.engine.tools.WordlistRef

/** Structural caps that never change per run (spec D3, G4, G11). */
object ScriptCaps {
    const val MAX_SOURCE_BYTES = 256 * 1024
    const val MAX_STRING_CHARS = 1_000_000
    const val MAX_ARRAY_LENGTH = 100_000
    const val MAX_BODY_BYTES = 1_000_000
    const val MAX_FINDING_FIELD_CHARS = 2_048
    const val MAX_LOG_LINES = 200
    const val MAX_LOG_LINE_CHARS = 500
    const val MAX_CONVERT_DEPTH = 8
    const val MAX_CONVERT_NODES = 200_000
    const val MAX_OBJECT_KEYS = 2_000
    const val MAX_OPEN_HANDLES = 8
    const val MAX_REDIRECTS = 5
    const val MANY_MAX_CONCURRENCY = 16
    const val MIN_TIMEOUT_MS = 100
    const val MAX_TIMEOUT_MS = 60_000
    const val DEFAULT_HTTP_TIMEOUT_MS = 5_000
    const val DEFAULT_TCP_MAX_BYTES = 4_096
    const val MAX_SLEEP_MS = 60_000
    const val MAX_REGEX_PATTERN_CHARS = 1_000
    const val MAX_HEADER_COUNT = 64
    const val MAX_HEADER_VALUE_CHARS = 8_192
    const val MAX_URL_CHARS = 8_192
    const val MAX_STACK_DEPTH = 300
    const val SCRIPT_THREAD_STACK_BYTES = 2L * 1024 * 1024
    const val MAX_SCRIPT_THREADS = 8
}

/**
 * Per-run budgets (spec section 8, G4, G11). The defaults are the shipped values; tests build smaller ones. [stallMs] is how long a script may
 * go without reaching the instruction observer or a host call before the watchdog abandons it (a runaway native call).
 */
data class ScriptLimits(
    val wallMs: Long = 10 * 60 * 1000L,
    val evalWallMs: Long = 2_000L,
    val maxHostCalls: Int = 20_000,
    val maxTransferBytes: Long = 50L * 1024 * 1024,
    val maxFindings: Int = 5_000,
    val maxFindingBytes: Long = 2L * 1024 * 1024,
    val maxInstructions: Long = 4_000_000_000L,
    val stallMs: Long = 30_000L,
    val watchdogTickMs: Long = 250L,
    val regexSteps: Long = 5_000_000L,
    val memoryFraction: Double = 0.85,
    /** Findings per second the script may emit once the burst is spent (G11). */
    val findingsPerSecond: Int = 200,
    val findingsBurst: Int = 50,
) {
    companion object { val DEFAULT = ScriptLimits() }
}

enum class AbortReason { CANCELLED, WALL_CLOCK, INSTRUCTIONS, CALLS, TRANSFER, FINDINGS, FINDING_BYTES, MEMORY, STALLED }

/**
 * Ends a script from the host side. It extends Error, not Exception: Rhino lets a script's try/catch see only its own error types, and
 * neither catch nor finally runs for an Error thrown from a host function or the instruction observer (G4).
 */
class ScriptAbort(val reason: AbortReason, message: String) : Error(message, null, false, false)

/** A host API call that failed in a way the script can see and catch (bad argument, refused scope, I/O failure). Becomes a JS Error. */
open class ScriptHostError(message: String) : RuntimeException(message, null, false, false)

/** The network scope refused a destination (D4, G2, G3). */
class ScopeViolation(message: String) : ScriptHostError(message)

/** What the host hands over after a descriptor evaluation: the plain `tool` data (no Rhino objects) or why it failed. */
sealed interface ScriptEvaluation {
    /** [tool] holds only String, Double, Boolean, null, List and Map values; [hasRun] says whether `tool.run` is a function. */
    data class Loaded(val tool: Map<String, Any?>, val hasRun: Boolean) : ScriptEvaluation
    data class Failed(val message: String) : ScriptEvaluation
}

data class ScriptRunResult(val status: ToolRunStatus, val summary: String, val log: List<String>, val findings: Int)

/** Everything a run needs from the app side. */
class ScriptHost(
    val transport: ScriptTransport,
    val gate: ScopeGate,
    val control: RunControl,
    val progress: ToolProgress,
    /** Lines of a wordlist from [offset], at most [limit]; empty when the list is unknown. */
    val wordlist: suspend (ref: WordlistRef, offset: Int, limit: Int) -> List<String> = { _, _, _ -> emptyList() },
    /** The tool's declared network scope; `ctx.devices` refuses [NetworkScope.TARGET] (spec section 12). */
    val network: NetworkScope = NetworkScope.TARGET,
    /** The discovered devices of the current network, newest scan first; the host API cuts the list at [ScriptDevices.MAX_DEVICES]. */
    val devices: suspend () -> List<ScriptDevice> = { emptyList() },
)

class ScriptRunRequest(val source: String, val inputs: Map<String, Any?>, val host: ScriptHost)

interface ScriptEngine {
    /** Evaluates [source] in the sandbox with no `ctx` and a short wall clock, and returns the `tool` object as plain data. */
    suspend fun evaluate(source: String): ScriptEvaluation
    /** Evaluates the source and calls `tool.run(inputs, ctx)` on a script thread; cancelling the caller stops the script and closes its sockets. */
    suspend fun run(request: ScriptRunRequest): ScriptRunResult
}
