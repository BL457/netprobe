package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.ChoiceOption
import com.northwalessolutions.netprobe.engine.tools.FindingKind
import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import com.northwalessolutions.netprobe.engine.tools.ToolIcon
import com.northwalessolutions.netprobe.engine.tools.ToolInputs
import com.northwalessolutions.netprobe.engine.tools.ToolModule
import com.northwalessolutions.netprobe.engine.tools.ToolRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.engine.tools.ToolTarget
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

/** The three bundled example scripts, read from the real asset files: each parses to the expected descriptor and runs against the fake transport. */
class ScriptExamplesTest {
    private val assetDir = File("src/main/assets/tools")
    private val env = NetworkEnv(Ipv4.parse("192.168.1.50")!!, 24, null, emptyList(), null, null, Transport.WIFI)
    private val transport = FakeScriptTransport()
    private val engine = RhinoScriptEngine(ScriptLimits(findingsPerSecond = 1_000_000, findingsBurst = 1_000_000))
    private val log = ArrayList<String>()

    private fun store(assets: ScriptAssets = ScriptAssets { name -> File(assetDir, name).takeIf { it.isFile }?.readText() }) = ScriptToolStore(
        assets, engine, { d, src -> ScriptToolModule(d, src, engine, { env }, { null }, transportFor = { _, _ -> transport }) }, { log += it })

    private fun examples(): Map<String, ToolModule> = runBlocking { store().loadExamples() }.associateBy { it.descriptor.key }

    private fun run(m: ToolModule, target: ToolTarget?, inputs: ToolInputs = ToolInputs.NONE): Pair<com.northwalessolutions.netprobe.engine.tools.ToolResult, RecordingProgress> {
        val progress = RecordingProgress()
        return runBlocking { m.run(target, inputs, FakeControl(), progress) } to progress
    }

    private val target = ToolTarget(1L, Ipv4.parse("192.168.1.20")!!, 8080, false, null)

    @Test fun `the three examples load under the examples category`() {
        val all = examples()
        assertEquals(setOf("example:http-headers", "example:banner-grab", "example:dns-lookup"), all.keys)
        assertTrue(log.toString(), log.isEmpty())
        assertTrue(all.values.all { it.descriptor.category == ToolCategory.EXAMPLES && it.descriptor.source is ToolSource.Script && it.descriptor.author == "NetProbe" })
        assertEquals(ScriptToolStore.EXAMPLE_FILES, listOf("http-headers.js", "banner-grab.js", "dns-lookup.js"))
        val source = all.getValue("example:http-headers").descriptor.source as ToolSource.Script
        assertEquals(File("assets/tools/http-headers.js"), source.file); assertEquals(64, source.sha256.length)
    }

    @Test fun `http headers parses to the expected descriptor`() {
        val d = examples().getValue("example:http-headers").descriptor
        assertEquals("HTTP headers", d.name); assertEquals("1.0", d.version); assertEquals(ToolIcon.WEB, d.icon)
        assertEquals(TargetKind.PORT, d.target); assertEquals(NetworkScope.TARGET, d.network)
        assertEquals(listOf(InputSpec.Text("path", "Path", null, false, "/", "/")), d.inputs)
    }

    @Test fun `banner grab parses to the expected descriptor`() {
        val d = examples().getValue("example:banner-grab").descriptor
        assertEquals("Banner grab", d.name); assertEquals(ToolIcon.TERMINAL, d.icon); assertEquals(TargetKind.PORT, d.target); assertEquals(NetworkScope.TARGET, d.network)
        assertEquals(listOf(
            InputSpec.Text("send", "Send first", "A line of text sent right after connecting.", false, null, "Leave empty to just listen"),
            InputSpec.Number("waitMs", "Wait (ms)", null, false, 3000, 200, 15000),
        ), d.inputs)
    }

    @Test fun `dns lookup parses to the expected descriptor`() {
        val d = examples().getValue("example:dns-lookup").descriptor
        assertEquals("DNS lookup", d.name); assertEquals(ToolIcon.DNS, d.icon); assertEquals(TargetKind.NONE, d.target); assertEquals(NetworkScope.ANY, d.network)
        assertEquals(listOf(InputSpec.Text("name", "Name or address", null, true, null, "example.com or 192.168.1.1")), d.inputs)
    }

    @Test fun `http headers runs and lists the headers`() {
        val (r, progress) = run(examples().getValue("example:http-headers"), target, ToolInputs(mapOf("path" to "docs/index.html")))
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("200 OK", r.summary)
        assertEquals(listOf("Server: fake", "Set-Cookie: a=1, b=2"), progress.findings.map { it.value })
        assertTrue(progress.findings.all { it.kind == FindingKind.HEADER })
        assertEquals(listOf("http GET http://192.168.1.20:8080/docs/index.html -> 192.168.1.20:8080"), transport.calls.toList())
        assertEquals(listOf<List<Any?>>(listOf(1, 1, 0, 0)), progress.updates.toList())
    }

    @Test fun `http headers uses https when the port is tls and the default path otherwise`() {
        run(examples().getValue("example:http-headers"), ToolTarget(1L, target.ip, 443, true, null))
        assertEquals("http GET https://192.168.1.20/ -> 192.168.1.20:443", transport.calls.single())
    }

    @Test fun `banner grab shows the first line and the first bytes`() {
        val (r, progress) = run(examples().getValue("example:banner-grab"), ToolTarget(1L, target.ip, 22, false, null))
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("14 bytes", r.summary)
        assertEquals(listOf(FindingKind.PROBE, FindingKind.PROBE), progress.findings.map { it.kind })
        assertEquals("SSH-2.0-fake", progress.findings[0].value); assertEquals("14 bytes in 2 ms", progress.findings[0].detail)
        assertEquals("5353482d322e302d66616b650d0a", progress.findings[1].value)
    }

    @Test fun `banner grab sends the text with a line ending and says so when nothing comes back`() {
        var sent = ""
        transport.exchangeHandler = { _, c -> sent = String(c.send); StreamReply(ByteArray(0), true, 1) }
        val (r, progress) = run(examples().getValue("example:banner-grab"), ToolTarget(1L, target.ip, 25, false, null), ToolInputs(mapOf("send" to "HELO x", "waitMs" to 500)))
        assertEquals("HELO x\r\n", sent); assertEquals("Nothing received", r.summary); assertTrue(progress.findings.isEmpty())
    }

    @Test fun `dns lookup needs the confirmation and then lists addresses`() {
        val m = examples().getValue("example:dns-lookup")
        assertTrue(run(m, null, ToolInputs(mapOf("name" to "example.com"))).first.summary.contains("confirmation"))
        transport.resolveHandler = { listOf(InetAddress.getByAddress(it.name, byteArrayOf(93, -72, -40, 34)), InetAddress.getByAddress(it.name, byteArrayOf(93, -72, -40, 35))) }
        val (r, progress) = run(m, null, ToolInputs(mapOf("name" to " Example.com ", ScriptToolModule.CONFIRM_ANY to true)))
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("2 addresses", r.summary)
        assertEquals(listOf("93.184.216.34", "93.184.216.35"), progress.findings.map { it.value })
        assertEquals(setOf("address"), progress.findings.map { it.extra["kind"] }.toSet())
        assertEquals(listOf("resolve example.com"), transport.calls.toList())
    }

    @Test fun `dns lookup reverses an address`() {
        val m = examples().getValue("example:dns-lookup")
        val (r, progress) = run(m, null, ToolInputs(mapOf("name" to "192.168.1.1", ScriptToolModule.CONFIRM_ANY to true)))
        assertEquals("host.example", r.summary); assertEquals(listOf("host.example"), progress.findings.map { it.value }); assertEquals("name", progress.findings.single().extra["kind"])
        transport.reverseHandler = { null }
        assertEquals("No name found", run(m, null, ToolInputs(mapOf("name" to "192.168.1.1", ScriptToolModule.CONFIRM_ANY to true))).first.summary)
        transport.calls.clear()
        val loopback = run(m, null, ToolInputs(mapOf("name" to "127.0.0.1", ScriptToolModule.CONFIRM_ANY to true))).first
        assertEquals(ToolRunStatus.FAILED, loopback.status); assertTrue(transport.calls.isEmpty())
    }

    @Test fun `a blank name is a failed run and the examples join the registry`() {
        val all = examples()
        val (r, _) = run(all.getValue("example:dns-lookup"), null, ToolInputs(mapOf("name" to "   ", ScriptToolModule.CONFIRM_ANY to true)))
        assertEquals(ToolRunStatus.FAILED, r.status)
        val registry = ToolRegistry(emptyList())
        registry.setScriptTools(all.values.toList())
        assertNotNull(registry.byKey("example:banner-grab"))
        assertEquals(listOf(ToolCategory.EXAMPLES), registry.all.value.map { it.descriptor.category }.distinct())
    }

    @Test fun `the store skips a missing or broken script and says why`() {
        val broken = mapOf("http-headers.js" to "tool = {", "banner-grab.js" to "tool = { id: 'ab', name: 'x', target: 'port', run: function () {} };", "dns-lookup.js" to null)
        val loaded = runBlocking { store(ScriptAssets { broken[it] }).loadExamples() }
        assertTrue(loaded.isEmpty())
        assertEquals(3, log.size)
        assertTrue(log.toString(), log[0].startsWith("example script http-headers.js:") && log[1].contains("The id must be 3 to 40") && log[2] == "example script missing: dns-lookup.js")
    }

    @Test fun `the example sources are plain ES5 with no regex literals or banned calls`() {
        for (name in ScriptToolStore.EXAMPLE_FILES) {
            val text = File(assetDir, name).readText()
            assertTrue(name, !text.contains("=>") && !Regex("\\b(let|const|class)\\b").containsMatchIn(text.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")))
            assertTrue(name, !text.contains(0x2014.toChar()))
            assertTrue(name, !Regex("\\beval\\b|\\bFunction\\(|\\bnew RegExp").containsMatchIn(text))
        }
    }
}
