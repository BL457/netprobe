package com.northwalessolutions.netprobe.data

import org.junit.Assert.*
import org.junit.Test

/** spec 4.7, Review Focus 4: one run per (host, tool, port), newest DONE preferred, else newest STOPPED or INTERRUPTED; never FAILED or RUNNING. */
class ToolHintsRuleTest {
    private var seq = 0L
    private fun run(tool: String, port: Int, status: ToolRunStatus, startedAt: Long) =
        ToolRunEntity(id = ++seq, hostId = 1, toolId = tool, port = port, startedAt = startedAt, finishedAt = startedAt + 1, status = status, optionsJson = "{}", summary = "")

    @Test fun `a done run on 5000 is not hidden by a later empty run on 80`() {
        val a = run("http-fingerprint", 5000, ToolRunStatus.DONE, 10)
        val b = run("http-fingerprint", 80, ToolRunStatus.DONE, 20)
        assertEquals(setOf(a.id, b.id), ToolHintsRule.pick(listOf(b, a)).toSet())
    }
    @Test fun `a newer stopped run never hides an older done run on the same port`() {
        val done = run("http-fingerprint", 5000, ToolRunStatus.DONE, 10)
        val stopped = run("http-fingerprint", 5000, ToolRunStatus.STOPPED, 20)
        assertEquals(listOf(done.id), ToolHintsRule.pick(listOf(stopped, done)))
    }
    @Test fun `with no done run the newest stopped or interrupted wins`() {
        val old = run("snmp-walk", 161, ToolRunStatus.INTERRUPTED, 10)
        val new = run("snmp-walk", 161, ToolRunStatus.STOPPED, 20)
        assertEquals(listOf(new.id), ToolHintsRule.pick(listOf(new, old)))
    }
    @Test fun `each tool and port is its own group`() {
        val tls443 = run("tls-certificate", 443, ToolRunStatus.DONE, 10)
        val tls8443 = run("tls-certificate", 8443, ToolRunStatus.DONE, 20)
        assertEquals(setOf(tls443.id, tls8443.id), ToolHintsRule.pick(listOf(tls8443, tls443)).toSet())
    }
}
