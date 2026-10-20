package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.ToolId
import java.util.Locale

/**
 * Every 3B tool string (spec 9): sentence case, active voice, no disclaimers, no thousands separators. Tools read their summaries and
 * finding details from here too, because both are shown to the Director. Templates live in ToolCopyTableTest.
 */
object ToolCopy {
    // Tool names
    const val DIR_BRUTEFORCE = "Directory brute-force"
    const val HTTP_FINGERPRINT = "HTTP fingerprint"
    const val TLS_CERTIFICATE = "TLS certificate"
    const val SMB_SESSION = "SMB session"
    const val SNMP_WALK = "SNMP walk"
    fun toolName(id: ToolId): String = when (id) {
        ToolId.DIR_BRUTEFORCE -> DIR_BRUTEFORCE; ToolId.HTTP_FINGERPRINT -> HTTP_FINGERPRINT; ToolId.TLS_CERTIFICATE -> TLS_CERTIFICATE
        ToolId.SMB_SESSION -> SMB_SESSION; ToolId.SNMP_WALK -> SNMP_WALK
    }

    // Tools row and blocks (8.1)
    const val TOOLS = "Tools"
    const val NO_TOOLS_RUN = "No tools run yet"
    const val NOT_RUN_YET = "Not run yet"
    const val RESULTS = "Results"
    const val RUN_TOOL = "Run tool"
    fun available(n: Int) = "$n available"
    fun portLabel(port: Int, snmp: Boolean) = if (snmp) "UDP 161" else "$port"
    fun rowHistory(relative: String, tool: String, portLabel: String, summary: String) = "Last run $relative: $tool on $portLabel, $summary"
    fun rowDescription(n: Int, expanded: Boolean) = "Tools, $n available, ${if (expanded) "collapse" else "expand"}"
    fun blockHistory(relative: String, portLabel: String, summary: String) = "Last run $relative on $portLabel: $summary"
    fun resultsDescription(tool: String) = "Show $tool results"
    fun chipDescription(tool: String, portLabel: String) = "Run $tool on port $portLabel"

    // Busy, Home, Quick scan all (4.3, 8.8)
    const val BUSY_TOOL = "A tool is already running on this device"
    const val BUSY_PORT_SCAN = "Wait for the port scan to finish"
    const val BUSY_TOO_MANY = "Four tools are already running, stop one first"
    const val SCAN_REFUSED = "A tool is running on this device"
    const val RUNNING_MARKER = "running"
    /** The Home row description item, joined with ", " by RowText.description. */
    fun rowSuffix(portScan: Boolean) = if (portScan) "port scan running" else "tool running"
    private fun devices(n: Int) = if (n == 1) "1 device" else "$n devices"
    /** The Quick scan all end message for failed and tool-skipped hosts; null when both are zero. */
    fun quickEnd(failed: Int, skipped: Int): String? = when {
        failed > 0 && skipped > 0 -> "Quick scan finished, ${devices(failed)} could not be scanned, $skipped skipped: a tool is running"
        failed > 0 -> "Quick scan finished, ${devices(failed)} could not be scanned"
        skipped > 0 -> "Quick scan finished, ${devices(skipped)} skipped: a tool is running"
        else -> null
    }

    // Pinned bar, progress, end message, statuses (8.2)
    const val STARTING = "starting"
    const val STOP_DESCRIPTION = "Stop tool run"
    fun pinnedLabel(tool: String, portLabel: String) = "$tool on $portLabel"
    fun progress(done: Int, total: Int?, found: Int, errors: Int, seconds: Long): String =
        if (total != null) "$done of $total, $found found${if (errors > 0) ", $errors errors" else ""}  $seconds s"
        else "$done done, $found found  $seconds s"
    fun finished(tool: String, summary: String) = "$tool finished: $summary"
    fun statusLabel(s: ToolRunStatus): String = when (s) {
        ToolRunStatus.RUNNING -> "Running"; ToolRunStatus.DONE -> "Done"; ToolRunStatus.STOPPED -> "Stopped"
        ToolRunStatus.FAILED -> "Failed"; ToolRunStatus.INTERRUPTED -> "Interrupted"
    }

    // Common summaries
    const val NETWORK_CHANGED = "Network changed, run stopped"
    const val BIND_FAILED = "Tool skipped: could not bind to Wi-Fi"
    fun failed(reason: String) = "Failed: $reason"
    fun interrupted(found: Int) = "Interrupted, $found found"
    fun stopped(done: Int, total: Int?, found: Int): String =
        if (total != null) "Stopped after $done of $total, $found found" else "Stopped after ${if (done == 1) "1 step" else "$done steps"}, $found found"

    // Brute-force summaries (5.5)
    const val BRUTE_CAP = "Stopped at 500 findings"
    const val BRUTE_RUNAWAY = "Stopped: the server answers every path alike"
    const val BRUTE_NO_ANSWER = "The server did not answer the first request"
    /** The reason inside failed(...) when no wordlist resolves at all, not even common-short (spec 5.1). */
    const val BRUTE_NO_WORDLIST = "Wordlist not found"
    /** A run whose chosen list was deleted ran common-short instead; [summary] is the run's own summary. */
    const val BRUTE_FALLBACK_PREFIX = "Wordlist not found, common-short used"
    fun bruteFallback(summary: String) = "$BRUTE_FALLBACK_PREFIX, $summary"
    /** Splits a stored summary into the fallback line (or null) and the rest, for the results header. */
    fun splitFallback(summary: String): Pair<String?, String> =
        if (summary.startsWith("$BRUTE_FALLBACK_PREFIX, ")) BRUTE_FALLBACK_PREFIX to summary.removePrefix("$BRUTE_FALLBACK_PREFIX, ") else null to summary
    fun bruteDone(found: Int, paths: Int, errors: Int) = "$found found in ${paths(paths)}${if (errors > 0) ", ${if (errors == 1) "1 error" else "$errors errors"}" else ""}"
    fun bruteStoppedAnswering(paths: Int) = "The server stopped answering after ${paths(paths)}"

    // Companion summaries (6.1 to 6.4)
    const val FINGERPRINT_NO_RESPONSE = "No HTTP response on this port"
    fun fingerprintSummary(name: String?, findings: Int) = "${name ?: "No product named"}, ${if (findings == 1) "1 finding" else "$findings findings"}"
    const val TLS_NO_HANDSHAKE = "No TLS handshake on this port"
    fun tlsSummary(name: String, date: String, selfSigned: Boolean) = "$name, expires $date${if (selfSigned) ", self-signed" else ""}"
    const val SMB_NO_DIALECT = "No SMB2 dialect in common"
    const val SMB_NO_RESPONSE = "No SMB response on this port"
    fun smbSummary(dialect: String, signingRequired: Boolean, anonymousAllowed: Boolean, guestAllowed: Boolean) =
        "$dialect, signing ${if (signingRequired) "required" else "not required"}, anonymous ${if (anonymousAllowed) "allowed" else "refused"}, guest ${if (guestAllowed) "allowed" else "refused"}"
    const val SNMP_NO_ANSWER = "No SNMP answer to public"
    fun snmpSummary(name: String, interfaces: Int) = "$name, ${if (interfaces == 1) "1 interface" else "$interfaces interfaces"}"

    // Options sheet (8.4) and its errors
    const val WORDLIST = "Wordlist"
    const val MANAGE_WORDLISTS = "Manage wordlists"
    const val EXTENSIONS = "Extensions"
    const val EXTENSIONS_PLACEHOLDER = "php, html, txt"
    const val EXTENSIONS_SUPPORT = "Each word is also tried with these endings"
    const val METHOD = "Method"
    const val HEAD_THEN_GET = "HEAD then GET"
    const val GET_ONLY = "GET only"
    const val CONCURRENCY = "Concurrency"
    const val TIMEOUT = "Timeout per request"
    const val REPORT_CODES = "Report status codes"
    const val DEFAULT_CODES_TEXT = "200, 204, 301, 302, 307, 401, 403"
    const val FILTER = "Filter not-found pages"
    const val FILTER_SUPPORT = "Checks random paths first and hides answers that match them"
    const val RUN = "Run"
    const val ERR_EXT_CHARS = "Use letters and digits only, for example php, html"
    const val ERR_EXT_TOO_MANY = "At most 8 extensions"
    const val ERR_CODE_RANGE = "Use codes from 100 to 599, for example 200, 301, 403"
    const val ERR_CODE_EMPTY = "Add at least one status code"
    const val WORDLIST_FALLBACK = "Wordlist not found, using common-short"
    fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"
    fun seconds(n: Int) = "$n s"
    fun paths(n: Int) = if (n == 1) "1 path" else "$n paths"

    // Results sheet (8.5)
    const val OLDER_RUN = "Older run"
    const val NEWER_RUN = "Newer run"
    const val OPEN_WITH = "Open with..."
    const val COPY = "Copy"
    const val SHARE = "Share"
    const val COPIED = "Copied"
    const val EMPTY_RUNNING = "Running, findings appear as they are found"
    const val NO_FINDINGS = "No findings"
    fun resultsTitle(tool: String, ip: String, port: Int) = "$tool  $ip:$port"
    fun resultsStatus(label: String, relative: String, nameSent: String?) = "$label, $relative${nameSent?.let { ", as $it" } ?: ""}"
    fun resultsRunning(progress: String) = "Running, $progress"
    fun runOf(n: Int, m: Int) = "Run $n of $m"
    fun openDescription(url: String) = "Open $url with"
    fun copyDescription(value: String) = "Copy $value"

    // Export (7.5)
    fun exportSubject(tool: String, ip: String, port: Int) = "NetProbe $tool $ip:$port"
    fun exportTitle(tool: String) = "NetProbe $tool"
    fun exportTarget(target: String) = "Target: $target"
    fun exportNameSent(name: String) = "Name sent: $name"
    fun exportStarted(at: String) = "Started: $at"
    fun exportFinished(at: String?) = "Finished: ${at ?: "not finished"}"
    fun exportStatus(label: String) = "Status: $label"
    fun exportSummary(summary: String) = "Summary: $summary"
    fun exportOptions(line: String) = "Options: $line"
    fun optionsLine(wordlist: String, entries: Int, extensions: List<String>, getOnly: Boolean, switchedToGetOnly: Boolean,
                    concurrency: Int, timeoutS: Int, codes: List<Int>, filter: Boolean): String {
        val method = when { switchedToGetOnly -> "GET only (HEAD and GET disagree)"; getOnly -> GET_ONLY; else -> HEAD_THEN_GET }
        return "wordlist $wordlist (${entries(entries)}), extensions ${extensions.joinToString(" ").ifEmpty { "none" }}, $method, " +
            "concurrency $concurrency, timeout $timeoutS s, codes ${codes.joinToString(" ")}, not-found filter ${if (filter) "on" else "off"}"
    }

    // Brute-force finding detail and sizes (5.5)
    /** "N B" up to 1023, one decimal "1.2 KB" above, "64 KB+" when truncated with no Content-Length, null when unknown. */
    fun size(bytes: Long?, truncatedUnknown: Boolean): String? = when {
        truncatedUnknown -> "64 KB+"
        bytes == null -> null
        bytes < 1024 -> "$bytes B"
        else -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    }
    fun pathDetail(status: Int, size: String?, location: String?): String =
        listOfNotNull(status.toString(), size).joinToString("  ") + (location?.let { "  to $it" } ?: "")

    // HTTP fingerprint details (6.1)
    const val GET_ROOT = "GET /"
    const val PAGE_TITLE = "Page title"
    const val GENERATOR_META = "Generator meta tag"
    const val DISALLOWED = "Disallowed in robots.txt"
    const val ALLOWED = "Allowed in robots.txt"
    const val PRESENT = "Present"
    const val ERROR_SIGNATURE = "Error page signature"
    fun getRootTo(location: String) = "GET /, to $location"
    fun getUrl(url: String) = "GET $url"
    fun cookieDetail(secure: Boolean, httpOnly: Boolean, sameSite: String?) =
        "Cookie" + (if (secure) ", Secure" else "") + (if (httpOnly) ", HttpOnly" else "") + (sameSite?.let { ", SameSite=$it" } ?: "")
    fun rules(n: Int) = if (n == 1) "1 rule" else "$n rules"
    fun urls(n: Int) = if (n == 1) "1 URL" else "$n URLs"
    fun contact(value: String) = "Contact: $value"
    fun matched(source: String, excerpt: String) = "Matched $source: $excerpt"

    // TLS details (6.2)
    const val SUBJECT = "Subject"; const val ISSUER = "Issuer"
    const val SAN_DNS = "Alternative name (DNS)"; const val SAN_IP = "Alternative name (IP)"
    const val VALID = "Valid"; const val EXPIRED = "Expired"; const val NOT_YET_VALID = "Not yet valid"
    const val ED25519 = "Ed25519"; const val ED448 = "Ed448"
    const val PUBLIC_KEY = "Public key"; const val SIGNATURE_ALGORITHM = "Signature algorithm"; const val SHA256_FINGERPRINT = "SHA-256 fingerprint"
    const val PROTOCOL_AND_CIPHER = "Protocol and cipher"; const val CERTIFICATE = "Certificate"
    const val SELF_SIGNED = "Self-signed"; const val WEAK_RSA = "RSA key under 2048 bit"; const val SHA1_SIGNED = "Signed with SHA-1"
    fun validityRange(from: String, to: String) = "$from to $to"
    fun expiresIn(days: Long) = if (days == 1L) "Expires in 1 day" else "Expires in $days days"
    fun keyRsa(bits: Int) = "RSA $bits bit"
    fun keyEc(bits: Int) = "EC $bits bit"
    fun keyDsa(bits: Int) = "DSA $bits bit"
    fun chainCertificate(n: Int, m: Int) = "Chain certificate $n of $m"
    fun andMore(n: Int) = "and $n more"

    // SMB details (6.3)
    const val DIALECT = "Dialect"; const val SIGNING = "Signing"; const val REQUIRED = "Required"; const val ENABLED_NOT_REQUIRED = "Enabled, not required"
    const val SESSION = "Session"; const val ANONYMOUS_ALLOWED = "Anonymous allowed"; const val ANONYMOUS_REFUSED = "Anonymous refused"
    const val GUEST_ALLOWED = "Guest allowed"; const val GUEST_MAPPED = "Guest mapped to guest"; const val GUEST_REFUSED = "Guest refused"
    const val NETBIOS_NAME = "NetBIOS name"; const val NETBIOS_DOMAIN = "NetBIOS domain"; const val DNS_NAME = "DNS name"; const val DNS_DOMAIN = "DNS domain"
    const val OS_BUILD = "OS build (NTLM)"
    fun dialectName(code: Int): String? = when (code) { 0x0202 -> "SMB 2.0.2"; 0x0210 -> "SMB 2.1"; 0x0300 -> "SMB 3.0"; 0x0302 -> "SMB 3.0.2"; else -> null }

    // SNMP details (6.4)
    const val SYS_DESCR = "sysDescr"; const val SYS_OBJECT_ID = "sysObjectID"; const val UPTIME = "Uptime"
    const val SYS_CONTACT = "sysContact"; const val SYS_NAME = "sysName"; const val SYS_LOCATION = "sysLocation"
    const val INTERFACE_ADDRESS = "Interface address"
    private val IF_STATUS = mapOf(1L to "up", 2L to "down", 3L to "testing", 4L to "unknown", 5L to "dormant", 6L to "notPresent", 7L to "lowerLayerDown")
    private val IF_TYPE = mapOf(6L to "ethernetCsmacd", 24L to "softwareLoopback", 53L to "propVirtual", 71L to "ieee80211", 117L to "gigabitEthernet",
        131L to "tunnel", 135L to "l2vlan", 161L to "ieee8023adLag", 209L to "bridge")
    /** TimeTicks (hundredths of a second): "12 days 4 h" from one day up, else "3 h 12 min". */
    fun uptime(ticks: Long): String {
        val s = ticks / 100; val days = s / 86_400; val hours = (s % 86_400) / 3_600; val minutes = (s % 3_600) / 60
        return if (days > 0) "${if (days == 1L) "1 day" else "$days days"} $hours h" else "$hours h $minutes min"
    }
    /** "<status>, <type>, <speed>, <MAC>" with empty parts omitted (spec 6.4). [speedBits] is bit/s; 0 or null omits the speed. */
    fun interfaceDetail(status: Long?, type: Long?, speedBits: Long?, mac: String?): String = buildList {
        status?.let { IF_STATUS[it] }?.let(::add)
        type?.let { add(IF_TYPE[it] ?: "type $it") }
        speedBits?.takeIf { it > 0 }?.let { add(if (it % 1_000_000_000L == 0L) "${it / 1_000_000_000L} Gbit/s" else "${it / 1_000_000L} Mbit/s") }
        mac?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(", ")
    fun address(addr: String, prefix: Int) = "$addr/$prefix"
}
