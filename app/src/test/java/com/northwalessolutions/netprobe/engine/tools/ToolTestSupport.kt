package com.northwalessolutions.netprobe.engine.tools

import java.net.Inet4Address
import java.net.InetAddress

object ToolTestSupport {
    class CollectingProgress : ToolProgress {
        val findings = mutableListOf<Finding>()
        var lastDone = 0; var lastTotal: Int? = null; var lastFound = 0
        override fun update(done: Int, total: Int?, found: Int, errors: Int) { lastDone = done; lastTotal = total; lastFound = found }
        override suspend fun finding(f: Finding) { findings += f }
        fun of(kind: FindingKind) = findings.filter { it.kind == kind }
        fun hints() = of(FindingKind.HINT).map { it.value }
    }
    fun control() = object : RunControl { override suspend fun gate() {}; override val pauseEpoch = 0; override fun excused(startEpoch: Int) = false }
    /** A test bumps [epoch] inside a fake exchange to simulate a pause that began while that exchange was in flight (spec 4.8). */
    class EpochControl : RunControl {
        var epoch = 0
        override suspend fun gate() {}
        override val pauseEpoch: Int get() = epoch
        override fun excused(startEpoch: Int) = startEpoch != epoch
    }
    fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
}
