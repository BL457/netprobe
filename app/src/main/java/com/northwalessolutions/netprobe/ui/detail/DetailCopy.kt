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
    fun setByYou(parts: String) = "Set by you: $parts"
}
