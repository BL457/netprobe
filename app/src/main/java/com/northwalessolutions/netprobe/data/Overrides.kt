package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.engine.identify.DeviceType

/** The Director's own values for a host (spec 4.1). null means "use the detected value". DeviceEdits.of arrives in Task 9. */
data class DeviceEdits(val name: String? = null, val type: DeviceType? = null, val vendor: String? = null, val model: String? = null)

/** hosts.identSoftware: software names joined by a newline, trimmed, distinct, in rule-file order (spec 3.6). */
object SoftwareColumn {
    private const val SEPARATOR = "\n"
    fun encode(names: List<String>): String? =
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    fun decode(column: String?): List<String> = column?.split(SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}

/** After a port scan the stored identity is replaced only by one at least as confident (spec 3.3); the DAO query is the atomic form. */
object IdentityWrite {
    fun replaces(storedConfidence: Int, newConfidence: Int): Boolean = newConfidence >= storedConfidence
}
