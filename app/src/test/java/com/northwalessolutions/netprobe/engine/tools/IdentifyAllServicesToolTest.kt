package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.portscan.PortTable
import com.northwalessolutions.netprobe.engine.serviceid.ActiveProbeBudget
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.FakeNetwork
import com.northwalessolutions.netprobe.engine.serviceid.ProbeMode
import com.northwalessolutions.netprobe.engine.serviceid.Reply
import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.serviceid.ServiceProbeRules
import com.northwalessolutions.netprobe.engine.serviceid.ServiceProbes
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.control
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.ip
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Identify all services (Director ruling R2) over the shipped probe file and a scripted network. */
class IdentifyAllServicesToolTest {
    private val table = PortTable.fromJson("""[{"port":22,"name":"SSH"},{"port":6379,"name":"Redis"},{"port":9999,"name":"Unknown"}]""")
    private val net = FakeNetwork()
    private val probes = ServiceProbeRules.load(listOf("src/main/res/raw", "app/src/main/res/raw").map { File("$it/service_probes.json") }.first { it.exists() }.readText()).probes
    private val identifier = ServiceIdentifier(table, ServiceProbes(probes, net::transport, clock = { net.clock.now }))
    private val applied = mutableListOf<Triple<Long, Int, ServiceInfo>>()
    private var budgetSeen: ActiveProbeBudget? = null
    private var onApplied: (Int) -> Unit = {}
    private var storedPorts = listOf(9999, 22, 6379)
    private val tool = IdentifyAllServicesTool(
        portsFor = { storedPorts },
        identify = { t, budget, onProbe ->
            budgetSeen = budget
            identifier.identifyTraced(t.ip, t.port, mode = ProbeMode.Active(budget, hellos = true), hostId = t.hostId, onProbe = onProbe)
        },
        apply = { h, p, i -> applied += Triple(h, p, i); onApplied(applied.size) },
        newBudget = { ActiveProbeBudget(IdentifyAllServicesTool.MAX_PORTS, IdentifyAllServicesTool.WALL_MS, IdentifyAllServicesTool.WALL_MS) { net.clock.now } })
    private fun target() = ToolTarget(9, ip("10.0.3.59"), 0, tls = false, serverName = null)
    private fun script() {
        net.respond = { port, _, sent ->
            when {
                port == 22 && sent == null -> Reply.text("SSH-2.0-OpenSSH_9.6p1 Debian-3\r\n")
                port == 6379 && sent != null && sent.contains("INFO") -> Reply.text("\$122\r\n# Server\r\nredis_version:7.2.4\r\nredis_git_sha1:00000000\r\n")
                else -> Reply.SILENT
            }
        }
    }

    @Test fun `it is a host tool named Identify all services and applies to no single port`() {
        assertEquals(ToolKeys.IDENTIFY_ALL, tool.descriptor.key); assertEquals("Identify all services", tool.descriptor.name)
        assertEquals(TargetKind.HOST, tool.descriptor.target); assertEquals(ToolCategory.IDENTITY, tool.descriptor.category)
        assertFalse(tool.applies(22, ServiceInfo("SSH", confidence = Confidence.PORT_TABLE)))
        assertEquals("identify-all-services", ToolKeys.IDENTIFY_ALL); assertEquals(ToolKeys.IDENTIFY_ALL, TestRegistry.registry().byKey("identify-all-services")?.descriptor?.key)
        assertFalse(ToolApplicability.appliesTo(ToolKeys.IDENTIFY_ALL, 22, ServiceInfo("SSH", confidence = Confidence.PORT_TABLE)))
        assertFalse(ToolKeys.IDENTIFY_ALL in ToolApplicability.toolsFor(TestRegistry.registry(), 22, ServiceInfo("SSH", confidence = Confidence.PORT_TABLE)).map { it.descriptor.key })
    }
    @Test fun `every stored port is run in order, each applied, the summary counts the ones named, progress ends at all of them`() = runTest {
        script()
        val p = CollectingProgress()
        val r = tool.run(target(), ToolInputs.NONE, control(), p)
        assertEquals(ToolRunStatus.DONE, r.status)
        assertEquals("2 of 3 ports named", r.summary)
        assertEquals(listOf(22, 6379), applied.map { it.second }.filter { it != 9999 }.sorted())
        assertTrue(applied.all { it.first == 9L })
        assertEquals(3, p.lastDone); assertEquals(3, p.lastTotal); assertEquals(2, p.lastFound)
        assertEquals(listOf("22: SSH OpenSSH 9.6p1", "6379: Redis 7.2.4", "9999: not recognised"), p.of(FindingKind.PROBE).map { it.value })
        assertEquals("one shared budget for the whole host", 64, budgetSeen!!.maxPorts)
        assertEquals(60_000L, budgetSeen!!.maxMs); assertEquals(60_000L, budgetSeen!!.hostWallMs)
    }
    @Test fun `a silent host says nothing was recognised`() = runTest {
        storedPorts = listOf(9999)
        val r = tool.run(target(), ToolInputs.NONE, control(), CollectingProgress())
        assertEquals("No services recognised", r.summary)
    }
    @Test fun `a host with no stored ports recognises nothing and sends nothing`() = runTest {
        storedPorts = emptyList()
        val p = CollectingProgress()
        val r = tool.run(target(), ToolInputs.NONE, control(), p)
        assertEquals("No services recognised", r.summary)
        assertEquals(0, p.lastTotal); assertTrue(net.connectLog().isEmpty())
    }
    @Test fun `progress is reported per port and the bar reads port N of M`() {
        assertEquals("port 1 of 3", ToolCopy.identifyAllProgress(0, 3))
        assertEquals("port 3 of 3", ToolCopy.identifyAllProgress(2, 3))
        assertEquals("port 3 of 3", ToolCopy.identifyAllProgress(3, 3))
    }
    @Test fun `more than 64 stored ports are cut at 64`() = runTest {
        storedPorts = (1..100).toList()
        val p = CollectingProgress()
        tool.run(target(), ToolInputs.NONE, control(), p)
        assertEquals(64, p.lastTotal)
    }
    @Test fun `stop part way keeps the ports already finished and applied`() = runTest {
        script()
        storedPorts = listOf(22, 6379, 9999)
        lateinit var job: Job
        onApplied = { n -> if (n == 1) job.cancel() }
        val p = CollectingProgress()
        job = launch(start = CoroutineStart.UNDISPATCHED) { tool.run(target(), ToolInputs.NONE, control(), p) }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf(22), applied.map { it.second })
        assertEquals("the finished port is reported before the stop is seen", 1, p.lastDone.coerceAtMost(1))
    }
}
