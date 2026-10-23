package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.portscan.PortTable
import com.northwalessolutions.netprobe.engine.serviceid.ActiveProbeBudget
import com.northwalessolutions.netprobe.engine.serviceid.FakeNetwork
import com.northwalessolutions.netprobe.engine.serviceid.LadderLimits
import com.northwalessolutions.netprobe.engine.serviceid.ProbeMode
import com.northwalessolutions.netprobe.engine.serviceid.Reply
import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.serviceid.ServiceProbeRules
import com.northwalessolutions.netprobe.engine.serviceid.ServiceProbes
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.control
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.ip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Identify service (3E spec 8) over the shipped probe file and a scripted network. */
class IdentifyServiceToolTest {
    private val table = PortTable.fromJson("""[{"port":22,"name":"SSH"},{"port":6379,"name":"Redis"},{"port":9999,"name":"Unknown"}]""")
    private val net = FakeNetwork()
    private val probes = ServiceProbeRules.load(listOf("src/main/res/raw", "app/src/main/res/raw").map { File("$it/service_probes.json") }.first { it.exists() }.readText()).probes
    private val identifier = ServiceIdentifier(table, ServiceProbes(probes, net::transport, clock = { net.clock.now }))
    private val applied = mutableListOf<Triple<Long, Int, ServiceInfo>>()
    private var budgetSeen: ActiveProbeBudget? = null
    private var unsafe = false
    private val tool = IdentifyServiceTool(
        identify = { t, budget, limits, onProbe ->
            budgetSeen = budget
            identifier.identifyTraced(t.ip, t.port, mode = ProbeMode.Active(budget, hellos = true, limits = limits), hostId = t.hostId, onProbe = onProbe)
        },
        plannedProbes = { port, limits -> identifier.plannedProbes(port, limits) },
        unsafeHellos = { unsafe },
        apply = { h, p, i -> applied += Triple(h, p, i) },
        newBudget = { ActiveProbeBudget(maxPorts = 1, maxMs = IdentifyServiceTool.NO_WALL_MS, hostWallMs = IdentifyServiceTool.NO_WALL_MS) { net.clock.now } })
    private fun target(port: Int) = ToolTarget(9, ip("10.0.3.59"), port, tls = false, serverName = null)

    @Test fun `redis is named, one probe finding per probe run, the summary names it and the port row is applied`() = runTest {
        net.respond = { _, _, sent ->
            if (sent != null && sent.contains("INFO")) Reply.text("\$122\r\n# Server\r\nredis_version:7.2.4\r\nredis_git_sha1:00000000\r\n") else Reply.SILENT
        }
        val p = CollectingProgress()
        val r = tool.run(target(6379), ToolInputs.NONE, control(), p)
        assertEquals(ToolRunStatus.DONE, r.status)
        assertEquals("Redis 7.2.4, 2 probes", r.summary)
        val lines = p.of(FindingKind.PROBE).map { it.value }
        assertEquals(listOf("null: 0 bytes, no match", "redis-info: 62 bytes, Redis 7.2.4"), lines)
        assertEquals(1, applied.size)
        assertEquals(9L, applied[0].first); assertEquals(6379, applied[0].second); assertEquals("Redis", applied[0].third.name); assertEquals("7.2.4", applied[0].third.version)
        assertEquals(2, p.lastDone)
    }
    @Test fun `a banner on the null read is one probe and the summary says so`() = runTest {
        net.respond = { _, _, sent -> if (sent == null) Reply.text("SSH-2.0-OpenSSH_9.6p1 Debian-3\r\n") else Reply.SILENT }
        val p = CollectingProgress()
        val r = tool.run(target(22), ToolInputs.NONE, control(), p)
        assertEquals("SSH OpenSSH 9.6p1, 1 probe", r.summary)
        assertEquals(listOf("null: 32 bytes, SSH OpenSSH 9.6p1"), p.of(FindingKind.PROBE).map { it.value })
        assertEquals(1, applied.size)
    }
    @Test fun `a silent port recognises nothing, says so, and still reports the probes sent`() = runTest {
        val p = CollectingProgress()
        val r = tool.run(target(9999), ToolInputs.NONE, control(), p)
        assertTrue(r.summary, Regex("No service recognised, \\d+ probes?").matches(r.summary))
        assertTrue(p.of(FindingKind.PROBE).isNotEmpty())
        assertTrue("nothing answered, so the port row is not written", applied.isEmpty())
    }
    @Test fun `the tool runs on a fresh budget of one port and no time limit regardless of any switch`() = runTest {
        tool.run(target(22), ToolInputs.NONE, control(), CollectingProgress())
        assertEquals(1, budgetSeen!!.maxPorts); assertEquals(Long.MAX_VALUE, budgetSeen!!.hostWallMs); assertEquals(Long.MAX_VALUE, budgetSeen!!.maxMs)
        assertEquals(1, budgetSeen!!.enteredPorts.coerceAtMost(1))
    }
    @Test fun `a silent unhinted port gets every safe hello, no cap, and the summary counts them`() = runTest {
        val p = CollectingProgress()
        val r = tool.run(target(9999), ToolInputs.NONE, control(), p)
        val ran = p.of(FindingKind.PROBE).map { it.value.substringBefore(":") }
        val planned = identifier.plannedProbes(9999, LadderLimits.thorough(false))
        assertEquals("null, the TLS check, 7 safe hellos", 9, planned)
        assertEquals("every planned connection was made", planned, net.connects.get())
        assertEquals("the scripted network refuses TLS, which leaves no trace line", planned - 1, ran.size)
        assertTrue("rtsp-options is rarity 4", "rtsp-options" in ran)
        assertTrue("sip-options is rarity 3 and past the scan cap of six", "sip-options" in ran)
        assertTrue("no careful probe with the switch off", ran.none { it == "mongodb-ismaster" || it == "smb2-negotiate" || it == "rdp-x224" })
        assertEquals("No service recognised, 8 probes", r.summary)
        assertEquals(8, p.lastDone)
    }
    @Test fun `with unsafe hellos on the careful probes follow the safe ones and the total grows`() = runTest {
        unsafe = true
        val p = CollectingProgress()
        val r = tool.run(target(9999), ToolInputs.NONE, control(), p)
        val ran = p.of(FindingKind.PROBE).map { it.value.substringBefore(":") }
        assertEquals(17, net.connects.get())
        assertTrue("mongodb-ismaster" in ran && "smb2-negotiate" in ran)
        assertTrue("safe before careful", ran.indexOf("rtsp-options") < ran.indexOf("rdp-x224"))
        assertEquals("No service recognised, 16 probes", r.summary)
    }
    @Test fun `identify service applies to any port and is a tool of its own`() {
        assertEquals(ToolKeys.IDENTIFY, tool.descriptor.key); assertEquals("Identify service", tool.descriptor.name)
        assertEquals(TargetKind.PORT, tool.descriptor.target); assertEquals(ToolCategory.IDENTITY, tool.descriptor.category)
        assertTrue(tool.applies(9999, ServiceInfo("Unknown", confidence = com.northwalessolutions.netprobe.engine.serviceid.Confidence.PORT_TABLE)))
        assertEquals("identify-service", ToolKeys.IDENTIFY)
        assertEquals(ToolKeys.IDENTIFY, TestRegistry.registry().byKey("identify-service")?.descriptor?.key)
        assertEquals(FindingKind.PROBE, FindingKind.parse("PROBE")); assertEquals(FindingKind.STACK, FindingKind.parse("STACK"))
    }
}
