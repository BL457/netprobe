package com.northwalessolutions.netprobe.ui.manual

import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.engine.tools.TestRegistry
import org.junit.Assert.*
import org.junit.Test

/** 3C spec 4.1 and 4.2 as screen state (8.2 item 6 drives the composable with what this produces). */
class ManualPresenterTest {
    private val reg = TestRegistry.registry()
    private val home = SubnetMath.of(Ipv4.parse("10.0.3.20")!!, 24)
    private val now = 10 * 60_000L
    private fun present(form: ManualForm, lan: Boolean = true, known: KnownTarget? = null, history: List<NetworkRun> = emptyList(), active: ToolRunner.Active? = null) =
        ManualPresenter.present(reg, form, home, lan, known, history, active, null, null, null, null, now) { "3 Jan 2026" }
    private fun runOn(id: Long, tool: String, port: Int, summary: String) = ToolRunEntity(id = id, hostId = 9, toolId = tool, port = port,
        startedAt = now - 2 * 60_000L, finishedAt = now, status = ToolRunStatus.DONE, optionsJson = "{}", summary = summary)

    @Test fun `errors wait for the first Run tap`() {
        val typed = ManualForm(ipText = "10.0.3", portText = "")
        present(typed).let { assertNull(it.ipError); assertNull(it.portError) }
        present(typed.copy(attempted = true)).let { assertEquals(ManualCopy.ERR_IP, it.ipError); assertEquals(ManualCopy.ERR_PORT_NEEDED, it.portError) }
    }
    @Test fun `each tool's option row and port field`() {
        present(ManualForm(tool = ToolKeys.SMB_SESSION)).let { assertFalse(it.portEnabled); assertFalse(it.showHttps); assertEquals(ManualCopy.FIXED_SMB, it.fixedLine) }
        present(ManualForm(tool = ToolKeys.SNMP_WALK)).let { assertFalse(it.portEnabled); assertEquals(ManualCopy.FIXED_SNMP, it.fixedLine) }
        present(ManualForm(tool = ToolKeys.TLS_CERTIFICATE)).let { assertTrue(it.portEnabled); assertFalse(it.showHttps); assertNull(it.fixedLine) }
        for (t in listOf(ToolKeys.HTTP_FINGERPRINT, ToolKeys.DIR_BRUTEFORCE)) present(ManualForm(tool = t)).let { assertTrue(it.showHttps); assertNull(it.fixedLine) }
    }
    @Test fun `Use HTTPS starts from the port or the known service and keeps the Director's choice`() {
        assertTrue(present(ManualForm(portText = "443")).https)
        assertFalse(present(ManualForm(portText = "8080")).https)
        val nas = KnownTarget(TestHosts.host(ip = "10.0.3.59", mdns = "CHNAS"), mapOf(5000 to ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED)))
        assertTrue(present(ManualForm(ipText = "10.0.3.59", portText = "5000"), known = nas).https)
        assertFalse(present(ManualForm(portText = "443", httpsChoice = false)).https)
    }
    @Test fun `a known device names itself and no LAN replaces it`() {
        val nas = KnownTarget(TestHosts.host(ip = "10.0.3.59", mdns = "CHNAS"), emptyMap())
        assertEquals(ManualCopy.knownDevice("CHNAS"), present(ManualForm(ipText = "10.0.3.59"), known = nas).infoLine)
        present(ManualForm(ipText = "10.0.3.59"), lan = false, known = nas).let { assertEquals(ManualCopy.NO_LAN, it.infoLine); assertFalse(it.lan) }
        assertNull(present(ManualForm(ipText = "10.20.0.5")).infoLine)
    }
    @Test fun `the run this screen started shows Stop, and its progress is live text, never screen state (F4)`() {
        val a = ToolRunner.Active(1, 9, ToolKeys.DIR_BRUTEFORCE, 5000, 3_000, 12, 300, 2, 0, false)
        assertTrue(present(ManualForm(), active = a).running); assertFalse(present(ManualForm()).running)
        assertEquals("12 of 300, 2 found  3 s", ManualPresenter.liveProgress(a, reg)); assertNull(ManualPresenter.liveProgress(null, reg))
        assertEquals("Running, 12 of 300, 2 found  3 s", ManualPresenter.liveResultsStatus(a, reg))
        val later = a.copy(activeMs = 9_000, done = 200, found = 7, errors = 1)
        assertEquals("a runner tick never changes the structural state", present(ManualForm(), active = ManualPresenter.frozen(a)),
            present(ManualForm(), active = ManualPresenter.frozen(later)))
        ManualPresenter.frozen(a).let { assertEquals(1L, it.runId); assertEquals(300, it.total); assertEquals(ToolKeys.DIR_BRUTEFORCE, it.toolKey) }
    }
    @Test fun `history rows read tool on ip and port, then summary and age`() {
        val rows = present(ManualForm(), history = listOf(NetworkRun(runOn(2, "http-fingerprint", 5000, "Synology DSM, 6 findings"), "10.0.3.5"),
            NetworkRun(runOn(3, "snmp-walk", 161, "gw, 3 interfaces"), "10.0.3.1"), NetworkRun(runOn(4, "no-such-tool", 1, "x").copy(toolName = "Old tool"), "10.0.3.9"),
            NetworkRun(runOn(5, "user:dns-lookup", 0, "3 records").copy(hostId = null, toolName = "DNS lookup"), null))).history
        assertEquals(listOf(2L, 3L, 4L, 5L), rows.map { it.runId })   // a removed tool keeps its stored name; a run with no device reads "No device"
        assertEquals("Old tool on 10.0.3.9:1", rows[2].title)
        assertEquals("DNS lookup on No device", rows[3].title); assertEquals("Show DNS lookup results for No device", rows[3].description)
        assertEquals("HTTP fingerprint on 10.0.3.5:5000", rows[0].title)
        assertEquals("Synology DSM, 6 findings, 2 minutes ago", rows[0].line)
        assertEquals("Show HTTP fingerprint results for 10.0.3.5:5000", rows[0].description)
        assertEquals("SNMP walk on 10.0.3.1:UDP 161", rows[1].title)
    }
}
