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
}
