package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.tools.DescriptorRules
import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.MacText
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import com.northwalessolutions.netprobe.engine.tools.ToolDescriptor
import com.northwalessolutions.netprobe.engine.tools.ToolIcon
import com.northwalessolutions.netprobe.engine.tools.ToolInputs
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.engine.tools.ToolTarget
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The broadcast descriptor flag, the MAC text rules and `inputs.mac` for host tools (spec section 11). */
class ScriptBroadcastMacTest {
    // descriptor

    private val base: Map<String, Any?> = mapOf("id" to "my-tool", "name" to "My tool", "target" to "host", "network" to "lan")
    private fun parse(tool: Map<String, Any?>) = ScriptDescriptorParser.parse(tool, true, "user:", null, ToolSource.BuiltIn)

    @Test fun `broadcast is false unless declared and is read for a lan tool`() {
        assertFalse((parse(base) as ParsedTool.Valid).descriptor.broadcast)
        assertTrue((parse(base + ("broadcast" to true)) as ParsedTool.Valid).descriptor.broadcast)
        assertFalse((parse(base + ("broadcast" to false)) as ParsedTool.Valid).descriptor.broadcast)
    }

    @Test fun `broadcast must be a boolean and needs the lan scope`() {
        assertEquals(listOf("The broadcast field must be true or false"), (parse(base + ("broadcast" to "yes")) as ParsedTool.Invalid).errors)
        assertEquals(listOf("A tool can declare broadcast only with the network set to lan"), (parse(base + mapOf("broadcast" to true, "network" to "target")) as ParsedTool.Invalid).errors)
        assertEquals(listOf("A tool can declare broadcast only with the network set to lan"), (parse(base + mapOf("broadcast" to true, "network" to "any")) as ParsedTool.Invalid).errors)
        assertEquals(listOf("A tool can declare broadcast only with the network set to lan"), (parse((base - "network") + ("broadcast" to true)) as ParsedTool.Invalid).errors)
    }

    // MacText

    @Test fun `a mac is normalised to lower case with colons or becomes empty`() {
        for (raw in listOf("AA:BB:CC:00:11:22", "aa-bb-cc-00-11-22", "aabb.cc00.1122", "AABBCC001122", " aa bb cc 00 11 22 ")) assertEquals(raw, "aa:bb:cc:00:11:22", MacText.normalize(raw))
        for (raw in listOf(null, "", "  ", "aa:bb:cc:00:11", "aa:bb:cc:00:11:22:33", "gg:bb:cc:00:11:22", "not a mac")) assertEquals(raw, "", MacText.normalize(raw))
    }

    // reserved name

    private fun descriptor(target: TargetKind, vararg inputs: InputSpec) =
        ToolDescriptor("user:t", "T", "", ToolIcon.TERMINAL, ToolCategory.MINE, target, inputs.toList(), NetworkScope.LAN)

    @Test fun `mac is reserved except as a text input of a host tool`() {
        val text = InputSpec.Text("mac", "MAC address")
        assertTrue(DescriptorRules.errors(descriptor(TargetKind.HOST, text)).isEmpty())
        for (target in listOf(TargetKind.PORT, TargetKind.NONE)) assertEquals(target.name, listOf("The input name mac is reserved"), DescriptorRules.errors(descriptor(target, text)))
        assertEquals(listOf("The input name mac is reserved"), DescriptorRules.errors(descriptor(TargetKind.HOST, InputSpec.Number("mac", "MAC"))))
        assertEquals(listOf("The input name mac is reserved"), DescriptorRules.errors(descriptor(TargetKind.HOST, InputSpec.Secret("mac", "MAC"))))
        assertTrue(DescriptorRules.errors(descriptor(TargetKind.HOST, InputSpec.Text("macs", "MACs"))).isEmpty())
    }

    @Test fun `the parser reports a reserved mac`() {
        val tool = base + mapOf("target" to "port", "inputs" to listOf(mapOf("name" to "mac", "type" to "text", "label" to "MAC")))
        assertEquals(listOf("The input name mac is reserved"), (parse(tool) as ParsedTool.Invalid).errors)
    }

    // inputs.mac through a module

    private val env = NetworkEnv(Ipv4.parse("192.168.1.50")!!, 24, null, emptyList(), null, null, Transport.WIFI)
    private val transport = FakeScriptTransport()
    private val control = FakeControl()
    private val progress = RecordingProgress()
    private val engine = RhinoScriptEngine(ScriptLimits(findingsPerSecond = 1_000_000, findingsBurst = 1_000_000))

    private fun module(script: String, targetKind: TargetKind = TargetKind.HOST, inputs: List<InputSpec> = emptyList(), broadcast: Boolean = false): ScriptToolModule {
        val d = ToolDescriptor("user:test-tool", "Test tool", "d", ToolIcon.TERMINAL, ToolCategory.MINE, targetKind, inputs, NetworkScope.LAN, "1", null, ToolSource.BuiltIn, broadcast)
        return ScriptToolModule(d, "tool = { id: 'test-tool', run: function (inputs, ctx) {\n$script\n} };", engine, { env }, { null as SocketBinder? }, transportFor = { _, _ -> transport })
    }
    private fun target(mac: String) = ToolTarget(7L, Ipv4.parse("192.168.1.20")!!, 0, false, null, mac = mac)
    private fun run(m: ScriptToolModule, t: ToolTarget, inputs: ToolInputs = ToolInputs.NONE) = runBlocking { m.run(t, inputs, control, progress) }

    @Test fun `a host tool receives the device mac, or an empty string when it is unknown`() {
        val m = module("return { summary: '[' + inputs.mac + ']' };")
        assertEquals("[aa:bb:cc:00:11:22]", run(m, target("aa:bb:cc:00:11:22")).summary)
        assertEquals("[]", run(m, target("")).summary)
    }

    @Test fun `a host tool's own mac input wins, and a blank one falls back to the device`() {
        val optional = module("return { summary: '[' + inputs.mac + ']' };", inputs = listOf(InputSpec.Text("mac", "MAC address", required = false)))
        assertEquals("[11:22:33:44:55:66]", run(optional, target("aa:bb:cc:00:11:22"), ToolInputs(mapOf("mac" to "11:22:33:44:55:66"))).summary)
        assertEquals("[aa:bb:cc:00:11:22]", run(optional, target("aa:bb:cc:00:11:22")).summary)
        assertEquals("[aa:bb:cc:00:11:22]", run(optional, target("aa:bb:cc:00:11:22"), ToolInputs(mapOf("mac" to "  "))).summary)
        assertEquals("[]", run(optional, target("")).summary)
        val required = module("return { summary: '[' + inputs.mac + ']' };", inputs = listOf(InputSpec.Text("mac", "MAC address")))
        assertEquals("[aa:bb:cc:00:11:22]", run(required, target("aa:bb:cc:00:11:22")).summary)
        val failed = run(required, target(""))
        assertEquals(ToolRunStatus.FAILED, failed.status); assertTrue(failed.summary, failed.summary.contains("mac Required"))
    }

    @Test fun `port tools get no mac`() {
        val portTarget = ToolTarget(7L, Ipv4.parse("192.168.1.20")!!, 80, false, null, mac = "aa:bb:cc:00:11:22")
        assertEquals("undefined", run(module("return { summary: typeof inputs.mac };", TargetKind.PORT), portTarget).summary)
    }

    @Test fun `a declared broadcast reaches the guard and an undeclared one does not`() {
        val send = "ctx.udp.send({ host: '192.168.1.255', port: 9, send: [255] }); return { summary: 'sent' };"
        val ok = run(module(send, broadcast = true), target(""))
        assertEquals(ToolRunStatus.DONE, ok.status); assertEquals("sent", ok.summary)
        assertTrue(transport.sentDatagrams.single().broadcast)
        transport.sentDatagrams.clear()
        val refused = run(module(send, broadcast = false), target(""))
        assertEquals(ToolRunStatus.FAILED, refused.status); assertTrue(refused.summary, refused.summary.contains("did not declare broadcast"))
        assertTrue(transport.sentDatagrams.isEmpty())
    }
}
