package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.BoundNetwork
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.FindingKind
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import com.northwalessolutions.netprobe.engine.tools.ToolDescriptor
import com.northwalessolutions.netprobe.engine.tools.ToolInputs
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.engine.tools.ToolTarget
import com.northwalessolutions.netprobe.engine.tools.WordlistRef
import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.ToolIcon
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.URLConnection

class ScriptToolModuleTest {
    private val env = NetworkEnv(Ipv4.parse("192.168.1.50")!!, 24, null, emptyList(), null, null, Transport.WIFI)
    private val target = ToolTarget(7L, Ipv4.parse("192.168.1.20")!!, 8080, false, "nas.local")
    private val transport = FakeScriptTransport()
    private val control = FakeControl()
    private val progress = RecordingProgress()
    private val engine = RhinoScriptEngine(ScriptLimits(findingsPerSecond = 1_000_000, findingsBurst = 1_000_000))
    private val seenBinders = ArrayList<SocketBinder?>()
    private val lanChecks = ArrayList<(InetAddress) -> Boolean>()
    private val binder = object : SocketBinder {
        override fun bind(socket: Socket) {}
        override fun bind(socket: DatagramSocket) {}
        override fun openConnection(url: URL): URLConnection = throw UnsupportedOperationException()
    }

    private fun module(
        script: String, targetKind: TargetKind = TargetKind.PORT, network: NetworkScope = NetworkScope.TARGET, inputs: List<InputSpec> = emptyList(),
        currentEnv: NetworkEnv? = env, lanBinder: ((NetworkEnv) -> SocketBinder?) = { binder },
    ): ScriptToolModule {
        val d = ToolDescriptor("user:test-tool", "Test tool", "d", ToolIcon.TERMINAL, ToolCategory.MINE, targetKind, inputs, network, "1", null, ToolSource.BuiltIn)
        return ScriptToolModule(d, "tool = { id: 'test-tool', run: function (inputs, ctx) {\n$script\n} };", engine, { currentEnv }, lanBinder,
            wordlistLines = { _, _, _ -> listOf("a", "b") },
            transportFor = { b, lan -> seenBinders += b; lanChecks += lan; transport })
    }

    private fun run(m: ScriptToolModule, t: ToolTarget? = target, inputs: ToolInputs = ToolInputs.NONE) =
        runBlocking { m.run(t, inputs, control, progress) }

    @Test fun `script tools are not offered on the device page`() {
        val m = module("")
        assertFalse(m.applies(80, ServiceInfo("HTTP", confidence = com.northwalessolutions.netprobe.engine.serviceid.Confidence.PORT_TABLE)))
        assertFalse(m.appliesToHost(com.northwalessolutions.netprobe.data.TestHosts.host()))
    }

    @Test fun `a port tool gets ip port and tls from the target when the inputs lack them`() {
        val r = run(module("return { status: 'done', summary: [inputs.ip, inputs.port, inputs.tls].join('|') };"))
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("192.168.1.20|8080|false", r.summary)
    }

    @Test fun `declared inputs arrive typed with defaults filled in`() {
        val specs = listOf(
            InputSpec.Text("path", "Path", default = "/x", required = false), InputSpec.Number("n", "N", default = 4, min = 1, max = 9), InputSpec.Bool("flag", "F", default = true),
            InputSpec.TextList("tags", "T", required = false), InputSpec.Secret("key", "K", required = false), InputSpec.Wordlist("words", "W", required = false),
        )
        val inputs = ToolInputs(mapOf("tags" to listOf("a", "b"), "key" to "s3", "words" to WordlistRef("wl", "List", 2)))
        val r = run(module("return { status: 'done', summary: JSON.stringify([inputs.path, inputs.n, inputs.flag, inputs.tags, inputs.key, inputs.words.name, ctx.wordlist('words')]) };", inputs = specs), inputs = inputs)
        assertEquals("""["/x",4,true,["a","b"],"s3","List",["a","b"]]""", r.summary)
    }

    @Test fun `check re-validates typed inputs against the descriptor`() {
        val m = module("", inputs = listOf(InputSpec.Number("n", "N", default = 4, min = 1, max = 9), InputSpec.Choice("c", "C", listOf(com.northwalessolutions.netprobe.engine.tools.ChoiceOption("a")), required = false)))
        assertTrue(m.check(ToolInputs(mapOf("ip" to Ipv4.parse("10.0.0.1"), "port" to 80, "tls" to false, "n" to 5))).isEmpty())
        assertEquals(setOf("n"), m.check(ToolInputs(mapOf("ip" to Ipv4.parse("10.0.0.1"), "port" to 80, "n" to 50))).keys)
        assertEquals(setOf("port", "c"), m.check(ToolInputs(mapOf("ip" to Ipv4.parse("10.0.0.1"), "port" to 70000, "n" to 5, "c" to "zzz"))).keys)
        assertEquals(setOf("ip", "port"), m.check(ToolInputs.NONE).keys)
    }

    @Test fun `a run with bad inputs fails before the script starts`() {
        val m = module("ctx.log('ran');", inputs = listOf(InputSpec.Number("n", "N", min = 1, max = 9)))
        val r = run(m, inputs = ToolInputs(mapOf("n" to 99)))
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("Failed: The inputs are not valid: n Use 9 or less", r.summary)
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun `a tool that needs a device fails without one and a none tool runs without one`() {
        assertEquals("Failed: This tool needs a device", run(module(""), t = null).summary)
        assertEquals("Failed: This tool needs a device", run(module("", targetKind = TargetKind.HOST), t = null).summary)
        val r = run(module("return { summary: typeof inputs.ip + typeof inputs.port };", targetKind = TargetKind.NONE), t = null)
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("undefinedundefined", r.summary)
    }

    @Test fun `a host tool receives only the ip`() {
        val r = run(module("return { summary: inputs.ip + typeof inputs.port };", targetKind = TargetKind.HOST))
        assertEquals("192.168.1.20undefined", r.summary)
    }

    @Test fun `network any needs the confirmation for every run`() {
        val m = module("ctx.dns.lookup('example.com'); return { summary: 'looked up' };", targetKind = TargetKind.NONE, network = NetworkScope.ANY)
        assertEquals("Failed: This tool can contact any address and needs your confirmation for each run", run(m, t = null).summary)
        assertTrue(transport.calls.isEmpty())
        assertEquals("Failed: This tool can contact any address and needs your confirmation for each run", run(m, t = null, inputs = ToolInputs(mapOf(ScriptToolModule.CONFIRM_ANY to false))).summary)
        val ok = run(m, t = null, inputs = ToolInputs(mapOf(ScriptToolModule.CONFIRM_ANY to true)))
        assertEquals(ToolRunStatus.DONE, ok.status); assertEquals("looked up", ok.summary)
        // the confirmation is the app's, never the script's: it is not passed on
        assertEquals("undefined", run(module("return { summary: typeof inputs.confirmAny };", targetKind = TargetKind.NONE, network = NetworkScope.ANY), t = null,
            inputs = ToolInputs(mapOf(ScriptToolModule.CONFIRM_ANY to true))).summary)
    }

    @Test fun `the confirmation is not recorded with the run`() {
        val m = module("return {};", targetKind = TargetKind.NONE, network = NetworkScope.ANY)
        val recorded = m.recorded(ToolInputs(mapOf(ScriptToolModule.CONFIRM_ANY to true, "x" to "y")))
        assertEquals(mapOf<String, Any?>("x" to "y"), recorded.values)
    }

    @Test fun `the scope guard sits in front of every call the script makes`() {
        val m = module("ctx.tcp.exchange({ host: '192.168.1.21', port: 22 }); return {};")
        val r = run(m)
        assertEquals(ToolRunStatus.FAILED, r.status); assertTrue(r.summary, r.summary.contains("only contact the device it was run against"))
        assertTrue(transport.calls.isEmpty())
        val ok = run(module("ctx.tcp.exchange({ host: inputs.ip, port: inputs.port }); ctx.tcp.exchange({ host: 'nas.local', port: 22 }); return {};"))
        assertEquals(ToolRunStatus.DONE, ok.status)
        assertEquals(listOf("tcp 192.168.1.20:8080", "tcp 192.168.1.20:22"), transport.calls.toList())
    }

    @Test fun `lan scope uses the current subnet and resolves names through the transport`() {
        transport.resolveHandler = { listOf(InetAddress.getByAddress(it.name, byteArrayOf(-64, -88, 1, 99))) }
        val m = module("ctx.tcp.exchange({ host: 'printer.local', port: 9100 }); return {};", targetKind = TargetKind.NONE, network = NetworkScope.LAN)
        assertEquals(ToolRunStatus.DONE, run(m, t = null).status)
        assertEquals(listOf("resolve printer.local", "tcp 192.168.1.99:9100"), transport.calls.toList())
        val outside = module("ctx.tcp.exchange({ host: '10.0.0.5', port: 22 }); return {};", targetKind = TargetKind.NONE, network = NetworkScope.LAN)
        assertTrue(run(outside, t = null).summary.contains("current network"))
    }

    @Test fun `lan scope without a network fails with a clear reason`() {
        val m = module("return {};", targetKind = TargetKind.NONE, network = NetworkScope.LAN, currentEnv = null)
        assertEquals("Failed: This tool needs a connected network", run(m, t = null).summary)
    }

    @Test fun `the transport is bound to the run's route, else to the lan, and knows what the lan is`() {
        run(module("return {};"))
        assertSame(binder, seenBinders.last())
        assertTrue(lanChecks.last()(InetAddress.getByName("192.168.1.77")))
        assertFalse(lanChecks.last()(InetAddress.getByName("192.168.2.77")))
        assertFalse(lanChecks.last()(InetAddress.getByName("8.8.8.8")))
        // a route chosen by the runner (a manual run) wins over the module's own
        val runnerBinder = object : SocketBinder by binder {}
        runBlocking { withContext(BoundNetwork(runnerBinder)) { module("return {};").run(target, ToolInputs.NONE, control, progress) } }
        assertSame(runnerBinder, seenBinders.last())
        run(module("return {};", lanBinder = { null }))
        assertNull(seenBinders.last())
        run(module("return {};", currentEnv = null))
        assertNull(seenBinders.last())
    }

    @Test fun `a failed script keeps its log as findings and returns a failed summary`() {
        val r = run(module("ctx.log('step one'); ctx.log('step two'); throw new Error('went wrong');"))
        assertEquals(ToolRunStatus.FAILED, r.status); assertTrue(r.summary, r.summary.startsWith("Failed: ") && r.summary.contains("went wrong"))
        assertEquals(listOf("step one", "step two"), progress.findings.map { it.value })
        assertTrue(progress.findings.all { it.kind == FindingKind.OTHER && it.extra == mapOf("kind" to "log") })
        val ok = run(module("ctx.log('noise'); return { summary: 'fine' };"))
        assertEquals("fine", ok.summary); assertEquals(2, progress.findings.size)     // no log findings for a run that succeeds
    }

    @Test fun `a script that says it failed is a failed run`() {
        val r = run(module("return { status: 'failed', summary: 'device said no' };"))
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("Failed: device said no", r.summary)
    }

    @Test fun `findings flow to the progress sink as they are made`() {
        val r = run(module("ctx.finding('HEADER', 'A: b'); ctx.progress(1, 2, 1); ctx.finding('PATH', '/x', 'found', 'http://192.168.1.20/x'); return {};"))
        assertEquals("2 findings", r.summary)
        assertEquals(listOf(FindingKind.HEADER, FindingKind.PATH), progress.findings.map { it.kind })
        assertEquals(listOf<List<Any?>>(listOf(1, 2, 1, 0)), progress.updates.toList())
    }
}
