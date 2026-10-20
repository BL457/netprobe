package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import org.junit.Assert.*
import org.junit.Test

/** spec 8.1, 8.2: the Tools block assembly, the history lines, the chips, the pinned-bar label and progress. */
class ToolsUiModelTest {
    private val now = 10_000_000L
    private fun host(snmp: String? = null) = HostEntity(id = 7, networkId = 1, ip = "10.0.3.59", mac = null, reverseDnsName = null, mdnsName = null, netbiosName = null,
        upnpName = null, upnpManufacturer = null, upnpModel = null, vendor = null, deviceType = DeviceType.NAS, customName = null, identVendor = null, identModel = null,
        identOs = null, identConfidence = 0, identReasons = emptyList(), snmpSysDescr = snmp, httpTitle = null, mdnsTxtJson = null, status = HostStatus.UNMARKED,
        note = "", isGateway = false, firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = 0)
    private fun svc(name: String) = ServiceInfo(name, confidence = Confidence.CONFIRMED)
    private val ports = listOf(22 to svc("SSH"), 5001 to svc("HTTPS"), 5000 to svc("HTTP"), 445 to svc("SMB"))
    private fun run(tool: ToolId, port: Int, at: Long, summary: String, status: ToolRunStatus = ToolRunStatus.DONE) =
        ToolRunEntity(id = at, hostId = 7, toolId = tool.key, port = port, startedAt = at, finishedAt = at + 1, status = status, optionsJson = "{}", summary = summary)

    @Test fun `blocks in tool order with chips in port order, snmp only with a discovery answer`() {
        val s = ToolsUiModel.of(host(), ports, emptyList(), now)!!
        assertEquals(listOf(ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT, ToolId.TLS_CERTIFICATE, ToolId.SMB_SESSION), s.blocks.map { it.toolId })
        assertEquals(4, s.available); assertEquals("No tools run yet", s.headerHistory)
        assertEquals(listOf("5000", "5001"), s.blocks[0].chips.map { it.label })
        assertEquals("Run Directory brute-force on port 5000", s.blocks[0].chips[0].description)
        assertEquals("Not run yet", s.blocks[0].history); assertFalse(s.blocks[0].hasRun)
        val snmp = ToolsUiModel.of(host(snmp = "Linux"), ports, emptyList(), now)!!.blocks.last()
        assertEquals(ToolId.SNMP_WALK, snmp.toolId); assertEquals(listOf("UDP 161"), snmp.chips.map { it.label })
        assertEquals("Run SNMP walk on port UDP 161", snmp.chips.single().description)
    }
    @Test fun `no applicable tool means no block`() {
        assertNull(ToolsUiModel.of(host(), listOf(22 to svc("SSH")), emptyList(), now))
    }
    @Test fun `history lines read the newest run`() {
        val runs = listOf(run(ToolId.DIR_BRUTEFORCE, 5000, now - 7_200_000, "7 found in 4614 paths"), run(ToolId.DIR_BRUTEFORCE, 5001, now - 9_000_000, "older"))
        val s = ToolsUiModel.of(host(), ports, runs, now)!!
        assertEquals("Last run 2 hours ago: Directory brute-force on 5000, 7 found in 4614 paths", s.headerHistory)
        assertEquals("Last run 2 hours ago on 5000: 7 found in 4614 paths", s.blocks[0].history); assertTrue(s.blocks[0].hasRun)
    }
    @Test fun `a running row with no summary yet reads its status label`() {
        val s = ToolsUiModel.of(host(), ports, listOf(run(ToolId.HTTP_FINGERPRINT, 5000, now, "", ToolRunStatus.RUNNING)), now)!!
        assertEquals("Last run just now on 5000: Running", s.blocks[1].history)
    }
    @Test fun `the pinned bar label, progress and fraction`() {
        fun active(done: Int, total: Int?, found: Int, ms: Long, tool: ToolId = ToolId.DIR_BRUTEFORCE, port: Int = 5000) =
            ToolRunner.Active(1, 7, tool, port, ms, done, total, found, 0, false)
        assertEquals(ToolRunBar("Directory brute-force on 5000", "starting", null), ToolsUiModel.bar(active(0, null, 0, 0)))
        assertEquals(ToolRunBar("Directory brute-force on 5000", "1234 of 4614, 7 found  12 s", 1234f / 4614), ToolsUiModel.bar(active(1234, 4614, 7, 12_400)))
        assertEquals(ToolRunBar("SNMP walk on UDP 161", "12 done, 3 found  2 s", null), ToolsUiModel.bar(active(12, null, 3, 2_000, ToolId.SNMP_WALK, 161)))
        assertEquals("A tool is already running on this device", ToolsUiModel.busyMessage(HostBusyRegistry.Work.TOOL))
        assertEquals("Wait for the port scan to finish", ToolsUiModel.busyMessage(HostBusyRegistry.Work.PORT_SCAN))
    }
}
