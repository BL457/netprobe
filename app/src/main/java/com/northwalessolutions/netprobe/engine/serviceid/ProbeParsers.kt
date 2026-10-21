package com.northwalessolutions.netprobe.engine.serviceid

/** Code parsers behind the probe `parser` field: a binary reply in, one text line out, which the probe's regex matches then read. */
object ProbeParsers {
    val NAMES: Set<String> = emptySet()
    fun parse(name: String, reply: ByteArray): String? = null
}
