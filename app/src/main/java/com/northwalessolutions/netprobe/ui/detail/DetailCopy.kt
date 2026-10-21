package com.northwalessolutions.netprobe.ui.detail

/** Detail strings added in Phase 3A (spec 7); older Detail wording stays in DetailText. Every string has a CopyTableTest template. */
object DetailCopy {
    fun runs(software: String) = "Runs $software"
    const val MORE_OPTIONS = "More options"
    const val EDIT_DEVICE = "Edit device"
    const val REMOVE_DEVICE = "Remove device"
    fun editDescription(name: String) = "Edit device, $name"
    const val TRY_AS = "Try as"
    fun tryAsDescription(ip: String, port: Int, scheme: String) = "Open $ip:$port as $scheme"
    const val SCAN_BIND_FAILED = "Scan skipped: could not bind to Wi-Fi"
    fun identifying(ports: Int) = if (ports == 1) "identifying 1 port" else "identifying $ports ports"
    fun setByYou(parts: String) = "Set by you: $parts"
    private val NUMBER_WORDS = listOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")
    /** The refusal when [n] port scans already run (3D spec 3.3). [n] is spelled as a word, so changing MAX_PORT_SCANS cannot leave "Two" behind. */
    fun portScansTooMany(n: Int): String {
        val word = NUMBER_WORDS.getOrNull(n) ?: n.toString()
        return if (n == 1) "$word port scan is already running, stop it in Scans" else "$word port scans are already running, stop one in Scans"
    }
}
