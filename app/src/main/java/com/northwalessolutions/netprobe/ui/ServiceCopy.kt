package com.northwalessolutions.netprobe.ui

/**
 * Strings of the Phase 3E service hellos that are not Detail wording (the port row lives in DetailCopy). Every string has a template
 * in ServiceCopyTest.
 */
object ServiceCopy {
    /** One line of "Why this identity": the port, what ran on it (the stack, else the product) and the probe or rule id that said so. */
    fun whyLine(port: Int, label: String, probe: String?): String = if (probe == null) "Port $port: $label" else "Port $port: $label ($probe)"
}
