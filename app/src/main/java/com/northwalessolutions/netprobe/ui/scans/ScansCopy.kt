package com.northwalessolutions.netprobe.ui.scans

/** Scans strings (3D spec 6, with the Director's "Scans" label). Task 5 adds the screen strings; templates live in ScansCopyTableTest. */
object ScansCopy {
    const val PORT_SCAN_NAME = "Port scan"
    const val INTERRUPTED = "Interrupted when NetProbe closed"
    private const val SECONDS_PER_MINUTE = 60L

    /** "8 s" or "2 min 4 s", and "2 min" on an exact minute. */
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
}
