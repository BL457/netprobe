package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.FindingKind
import com.northwalessolutions.netprobe.engine.tools.ToolInputs
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.engine.tools.ToolTarget
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The catalog's wake-on-lan.js run for real through ScriptToolModule, the real guard and a fake transport (spec section 11). */
class WakeOnLanScriptTest {
    private val source = File("../catalog/wake-on-lan.js").readText(Charsets.UTF_8).replace("\r\n", "\n")
    private val engine = RhinoScriptEngine(ScriptLimits(findingsPerSecond = 1_000_000, findingsBurst = 1_000_000))
    private val transport = FakeScriptTransport()
    private val progress = RecordingProgress()
    private fun env(prefix: Int = 24) = NetworkEnv(Ipv4.parse("192.168.1.50")!!, prefix, null, emptyList(), null, null, Transport.WIFI)

    private fun module(currentEnv: NetworkEnv? = env()): ScriptToolModule {
        val loaded = runBlocking { engine.evaluate(source) } as ScriptEvaluation.Loaded
        val d = (ScriptDescriptorParser.parse(loaded.tool, loaded.hasRun, ToolKeys.USER_PREFIX, null, ToolSource.BuiltIn) as ParsedTool.Valid).descriptor
        return ScriptToolModule(d, source, engine, { currentEnv }, { null }, transportFor = { _, _ -> transport })
    }
    private fun target(mac: String) = ToolTarget(1L, Ipv4.parse("192.168.1.20")!!, 0, false, null, mac = mac)
    private fun run(m: ScriptToolModule, mac: String, inputs: Map<String, Any?> = emptyMap()) =
        runBlocking { m.run(target(mac), ToolInputs(inputs), FakeControl(), progress) }

    @Test fun `it sends the magic packet three times to the directed broadcast address`() {
        val r = run(module(), "aa:bb:cc:00:11:22", mapOf("mac" to "aa:bb:cc:00:11:22"))
        assertEquals(r.summary, ToolRunStatus.DONE, r.status)
        assertEquals("Sent to aa:bb:cc:00:11:22", r.summary)
        val sent = transport.sentDatagrams
        assertEquals(3, sent.size)
        for (d in sent) {
            assertEquals("192.168.1.255", d.address.hostAddress); assertEquals(9, d.port); assertTrue(d.broadcast)
            assertEquals(102, d.data.size)
            assertTrue(d.data.take(6).all { it == (-1).toByte() })
            for (k in 0 until 16) assertEquals(listOf<Byte>(-86, -69, -52, 0, 0x11, 0x22), d.data.slice(6 + k * 6 until 12 + k * 6))
        }
        val f = progress.findings.single()
        assertEquals("Magic packet sent to aa:bb:cc:00:11:22 (3 times)", f.value)
        assertEquals(FindingKind.OTHER, f.kind); assertEquals("wake", f.extra["kind"])
    }

    @Test fun `the device's mac is used when the field is left empty`() {
        val r = run(module(), "aa:bb:cc:00:11:22")
        assertEquals(r.summary, ToolRunStatus.DONE, r.status)
        assertEquals(3, transport.sentDatagrams.size)
    }

    @Test fun `repeat and port are honoured`() {
        val r = run(module(), "", mapOf("mac" to "AA-BB-CC-00-11-22", "repeat" to 1, "udpPort" to 7))
        assertEquals("Sent to aa:bb:cc:00:11:22", r.summary)
        assertEquals(1, transport.sentDatagrams.size); assertEquals(7, transport.sentDatagrams.single().port)
        assertEquals("Magic packet sent to aa:bb:cc:00:11:22 (1 time)", progress.findings.single().value)
    }

    @Test fun `separators are optional and any spelling is accepted`() {
        for (mac in listOf("aabbcc001122", "AABB.CC00.1122", "aa bb cc 00 11 22", "Aa:Bb:Cc:00:11:22")) {
            transport.sentDatagrams.clear(); progress.findings.clear()
            val r = run(module(), "", mapOf("mac" to mac, "repeat" to 1))
            assertEquals("$mac ${r.summary}", ToolRunStatus.DONE, r.status)
            assertEquals(mac, 1, transport.sentDatagrams.size)
        }
    }

    @Test fun `a bad mac fails before anything is sent`() {
        for (mac in listOf("aa:bb:cc:00:11", "aa:bb:cc:00:11:22:33", "gg:bb:cc:00:11:22", "not a mac")) {
            val r = run(module(), "", mapOf("mac" to mac))
            assertEquals(mac, ToolRunStatus.FAILED, r.status)
            assertTrue(r.summary, r.summary.contains("The MAC address must be 12 hex digits"))
        }
        assertTrue(transport.sentDatagrams.isEmpty())
    }

    @Test fun `a missing mac is a required input error`() {
        val r = run(module(), "")
        assertEquals(ToolRunStatus.FAILED, r.status); assertTrue(r.summary, r.summary.contains("mac Required"))
        assertTrue(transport.sentDatagrams.isEmpty())
    }

    @Test fun `the broadcast address follows the network's prefix`() {
        val m = module(env(16))
        run(m, "aa:bb:cc:00:11:22", mapOf("mac" to "aa:bb:cc:00:11:22", "repeat" to 1))
        assertEquals("192.168.255.255", transport.sentDatagrams.single().address.hostAddress)
    }

    @Test fun `a network with no broadcast address fails clearly`() {
        val r = run(module(env(32)), "aa:bb:cc:00:11:22", mapOf("mac" to "aa:bb:cc:00:11:22"))
        assertEquals(ToolRunStatus.FAILED, r.status); assertTrue(r.summary, r.summary.contains("no broadcast address"))
    }

    @Test fun `out of range inputs are refused by the form rules`() {
        for (bad in listOf(mapOf("repeat" to 0), mapOf("repeat" to 11), mapOf("udpPort" to 0), mapOf("udpPort" to 65536))) {
            val r = run(module(), "aa:bb:cc:00:11:22", mapOf("mac" to "aa:bb:cc:00:11:22") + bad)
            assertEquals(bad.toString(), ToolRunStatus.FAILED, r.status)
        }
        assertTrue(transport.sentDatagrams.isEmpty())
    }

    @Test fun `the descriptor is as the catalog promises`() {
        val d = module().descriptor
        assertEquals("user:wake-on-lan", d.key); assertTrue(d.broadcast)
        assertEquals(listOf("mac", "udpPort", "repeat"), d.inputs.map { it.name })
    }
}
