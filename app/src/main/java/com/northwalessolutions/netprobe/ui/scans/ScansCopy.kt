package com.northwalessolutions.netprobe.ui.scans

/** Every Scans string (3D spec 6, with the Director's "Scans" label). Templates live in ScansCopyTableTest. */
object ScansCopy {
    const val TITLE = "Scans"
    const val RUNNING = "Running"
    const val RECENT = "Recent"
    const val EMPTY = "Nothing running"
    const val EMPTY_LINE = "Scans and tool runs show here while they run, and finished ones stay for a day"
    const val CLEAR = "Clear history"
    const val CLEARED = "History cleared"
    /** The same text as HomeCopy.UNDO. */
    const val UNDO = "Undo"
    const val RADAR = "Radar scan"
    const val QUICK = "Quick scan all"
    /** The tool name in ToolCopy.finished: "Port scan finished: Done in 14 s, 5 open ports". */
    const val PORT_SCAN_NAME = "Port scan"
    const val INTERRUPTED = "Interrupted when NetProbe closed"
    const val STOP = "Stop"
    /** A row whose device is neither named nor in the history (a removed host's old outcome never reaches the screen, so this is rare). */
    const val UNKNOWN_HOST = "device"
    private const val SECONDS_PER_MINUTE = 60L

    fun portScan(host: String) = "Port scan on $host"
    /** The port is the number the run used: 161 for the SNMP walk. */
    fun tool(tool: String, host: String, port: Int) = "$tool on $host:$port"
    /** The title of a tool row; a run with no device has no port to show, so it reads "<tool> on No device". */
    fun toolLine(tool: String, host: String, port: Int, noDevice: Boolean) = if (noDevice) "$tool on $host" else tool(tool, host, port)
    fun wordlistImport(host: String) = "Wordlist from $host"
    fun quickProgress(done: Int, total: Int, seconds: Long) = "$done of $total devices, $seconds s"

    /** "8 s" or "2 min 4 s", and "2 min" on an exact minute; never negative. */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        if (s < SECONDS_PER_MINUTE) return "$s s"
        val m = s / SECONDS_PER_MINUTE; val r = s % SECONDS_PER_MINUTE
        return if (r == 0L) "$m min" else "$m min $r s"
    }
    private fun devices(n: Int) = if (n == 1) "1 device" else "$n devices"
    fun radarDone(found: Int, seconds: Long) = "Done in ${duration(seconds)}, ${if (found == 0) "no devices" else devices(found)}"
    fun quickDone(scanned: Int, seconds: Long) = "Done in ${duration(seconds)}, ${if (scanned == 0) "no devices" else devices(scanned)} scanned"
    fun portDone(open: Int, seconds: Long) =
        "Done in ${duration(seconds)}, ${when (open) { 0 -> "no open ports"; 1 -> "1 open port"; else -> "$open open ports" }}"
    fun stoppedAfter(seconds: Long) = "Stopped after ${duration(seconds)}"
    fun recentLine(summary: String, relative: String) = "$summary, $relative"
    /** The Stop pill's accessibility description: the title with its first letter lower-cased. */
    fun stopDescription(title: String) = "Stop " + title.replaceFirstChar { it.lowercase() }
}
