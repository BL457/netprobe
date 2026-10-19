package com.northwalessolutions.netprobe.engine.portscan

import org.junit.Assert.*
import org.junit.Test

class PortTableTest {
    private val json = """[{"port":22,"proto":"tcp","name":"SSH"},{"port":80,"proto":"tcp","name":"HTTP"},{"port":5900,"proto":"tcp","name":"VNC"}]"""
    @Test fun `looks up names and falls back to Unknown`() {
        val t = PortTable.fromJson(json)
        assertEquals("SSH", t.nameFor(22)); assertEquals("VNC", t.nameFor(5900)); assertEquals("Unknown", t.nameFor(31337))
    }
    @Test fun `malformed json yields an empty table not a crash`() {
        assertEquals("Unknown", PortTable.fromJson("not json").nameFor(22))
    }
    @Test fun `bundled table names every quick port`() {
        val t = PortTable.fromJson(javaClass.getResource("/ports.json")!!.readText())
        val unnamed = ScanProfile.Quick.ports.filter { t.nameFor(it) == "Unknown" }
        assertTrue("unnamed quick ports: $unnamed", unnamed.isEmpty())
    }
}
