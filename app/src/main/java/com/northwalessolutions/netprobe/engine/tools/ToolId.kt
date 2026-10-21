package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import java.net.Inet4Address

/** All tool coroutines and blocking calls run on Dispatchers.IO.limitedParallelism(TOOL_THREADS) (spec 4.4). */
const val TOOL_THREADS = 16

enum class ToolId(val key: String) {
    DIR_BRUTEFORCE("dir-bruteforce"), HTTP_FINGERPRINT("http-fingerprint"),
    TLS_CERTIFICATE("tls-certificate"), SMB_SESSION("smb-session"), SNMP_WALK("snmp-walk");
    companion object { fun byKey(key: String): ToolId? = entries.firstOrNull { it.key == key } }
}

/** Finding kinds (spec 7.1); [parse] falls back to OTHER so a kind removed in a later version never crashes a read. */
enum class FindingKind {
    PATH, STATUS, HEADER, COOKIE, TITLE, GENERATOR, ROBOTS, SITEMAP, SECURITY_TXT, ERROR_PAGE, FRAMEWORK,
    CERT_SUBJECT, CERT_ISSUER, CERT_SAN, CERT_VALIDITY, CERT_KEY, CERT_SIGNATURE, CERT_FINGERPRINT, CERT_CHAIN, TLS_SESSION, FLAG,
    SMB_DIALECT, SMB_SIGNING, SMB_SESSION, SMB_HOST, SNMP_SYSTEM, SNMP_INTERFACE, SNMP_ADDRESS, HINT, OTHER;
    companion object { fun parse(s: String): FindingKind = entries.firstOrNull { it.name == s } ?: OTHER }
}

data class ToolTarget(
    val hostId: Long, val ip: Inet4Address, val port: Int,
    val tls: Boolean,                // HTTPS for the web tools; always true for TLS certificate; false otherwise
    val serverName: String?,         // known name for SNI and Host (4.9); null when none or for brute-force
    val route: ToolRoute? = null,    // manual runs only (3C spec 4.5): the runner's routeBind; null keeps the scan binding
)

/** Run-wide controls owned by the runner: the pause gate, the rate ceiling and the pause epoch (spec 4.8, 5.3). */
interface RunControl {
    suspend fun gate()                       // suspends while paused, then takes one rate token for this host
    val pauseEpoch: Int                      // incremented on every pause
    fun excused(startEpoch: Int): Boolean    // true when a pause began after startEpoch, or the run is paused now
}

interface ToolProgress {
    fun update(done: Int, total: Int?, found: Int, errors: Int = 0)   // total null when unknown (SNMP walk)
    suspend fun finding(f: Finding)                                   // persisted immediately (spec 4.6)
    /** Records a value learned mid-run into the run's optionsJson, for example methodUsed after calibration (spec 7.1). */
    suspend fun option(key: String, value: String) {}
    /** As [option], written as a JSON number, for example wordlistEntries after a wordlist fallback. */
    suspend fun option(key: String, value: Int) {}
}

/**
 * spec 4.8, the companion-tool rule: an exchange that failed while a pause began (or is under way) is retried once after the resume.
 * [failed] says whether a result is a failure; a success, or a failure with no pause involved, is returned as it is.
 */
suspend fun <T> RunControl.excusedOnce(failed: (T) -> Boolean, block: suspend () -> T): T {
    val epoch = pauseEpoch
    val first = block()
    if (!failed(first) || !excused(epoch)) return first
    gate()
    return block()
}

data class Finding(val kind: FindingKind, val value: String, val detail: String? = null, val url: String? = null, val extra: Map<String, String> = emptyMap())
data class ToolResult(val status: ToolRunStatus, val summary: String)   // DONE or FAILED; STOPPED is set by the runner

sealed interface ToolOptions {
    object None : ToolOptions
    /** [wordlistName] and [wordlistEntries] are recorded in optionsJson so history still names a deleted list (spec 7.1). */
    data class DirBrute(val options: DirBruteOptions, val wordlistName: String = "", val wordlistEntries: Int = 0) : ToolOptions
}

interface Tool {
    val id: ToolId
    val name: String
    fun applies(port: Int, service: ServiceInfo): Boolean
    fun appliesToHost(host: HostEntity): Boolean = false
    /** Suspends until done. Cancellation of the calling coroutine is the only stop. Calls control.gate() before every request. */
    suspend fun run(target: ToolTarget, options: ToolOptions, control: RunControl, progress: ToolProgress): ToolResult
}
