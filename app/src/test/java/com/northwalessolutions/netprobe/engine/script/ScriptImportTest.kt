package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import com.northwalessolutions.netprobe.ui.tools.ImportCopy
import com.northwalessolutions.netprobe.update.FetchError
import com.northwalessolutions.netprobe.update.HttpsUpdateFetcher
import com.northwalessolutions.netprobe.update.UpdateFetch
import com.northwalessolutions.netprobe.update.UpdateFetcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The import pipeline: file and URL reading, the sandboxed description read, the plan, the replace rules (G9) and the final save. No real network. */
class ScriptImportTest {
    @get:Rule val tmp = TemporaryFolder()
    private val env = NetworkEnv(Ipv4.parse("192.168.1.50")!!, 24, null, emptyList(), null, null, Transport.WIFI)
    private val engine = RhinoScriptEngine()
    private val root: File by lazy { File(tmp.root, "tools") }
    private val store: ScriptToolStore by lazy {
        ScriptToolStore(ScriptAssets { null }, engine, { d, src -> ScriptToolModule(d, src, engine, { env }, { null }) }, {}, root, { 7L })
    }

    private fun importer(fetcher: UpdateFetcher = Recording(), budget: Long = 20_000L, engine: ScriptEngine = this.engine) = ScriptImporter(engine, store, fetcher, budget)

    private fun script(id: String = "my-tool", version: String = "1.0", network: String = "target", target: String = "none", inputs: String = "", author: String = "Ann") =
        """tool = { id: "$id", name: "My tool", version: "$version", author: "$author", description: "Does a thing.", target: "$target", network: "$network", inputs: [$inputs], run: function (inputs, ctx) { return { status: "done" }; } };"""

    private fun text(name: String, type: String = "text") = """{ name: "$name", type: "$type", label: "${name.replaceFirstChar { it.uppercase() }}" }"""

    private fun file(source: String, name: String? = "tool.js") = runBlocking { importer().fromFile(name) { ByteArrayInputStream(source.toByteArray()) } }
    private fun ready(o: ImportOutcome): ImportPlan = (o as? ImportOutcome.Ready)?.plan ?: error("not ready: $o")
    private fun errors(o: ImportOutcome): List<String> = (o as? ImportOutcome.Failed)?.errors ?: error("not failed: $o")
    private fun install(source: String) { val p = ready(file(source)); assertTrue(runBlocking { importer().confirm(p, true) } is ScriptImporter.ConfirmResult.Saved) }

    // plan

    @Test fun `a file becomes a plan with the facts the sheet shows and nothing is saved`() {
        val plan = ready(file(script(network = "lan", target = "port", inputs = text("path")), name = "headers.js"))
        val d = plan.descriptor
        assertEquals(listOf("user:my-tool", "My tool", "1.0", "Ann", "Does a thing."), listOf(d.key, d.name, d.version, d.author, d.description))
        assertEquals(listOf(NetworkScope.LAN, TargetKind.PORT), listOf(d.network, d.target))
        assertEquals(listOf("Path"), d.inputs.map { it.label })
        assertEquals(ScriptBytes.sha256(plan.source), plan.sha256)
        assertEquals(64, plan.sha256.length)
        assertEquals(listOf(null, "headers.js"), listOf(plan.sourceUrl, plan.fileName))
        assertNull(plan.replace)
        assertFalse(root.exists())
        assertTrue(store.installed.value.isEmpty())
    }

    @Test fun `nothing but the description is evaluated`() {
        val calls = ArrayList<String>()
        val spy = object : ScriptEngine {
            override suspend fun evaluate(source: String): ScriptEvaluation { calls += "evaluate"; return engine.evaluate(source) }
            override suspend fun run(request: ScriptRunRequest): ScriptRunResult { calls += "run"; error("run must not be called at import") }
        }
        ready(runBlocking { importer(engine = spy).fromFile("t.js") { ByteArrayInputStream(script().toByteArray()) } })
        assertEquals(listOf("evaluate"), calls)
    }

    @Test fun `every problem in a description is listed at once`() {
        val bad = """tool = { id: "X", name: "", version: "!!", target: "sideways", network: "everywhere", inputs: [{ name: "a", type: "nope", label: "A" }], run: 5 };"""
        val list = errors(file(bad))
        assertTrue(list.toString(), list.size >= 5)
        assertEquals(list.distinct(), list)
        assertTrue(list.contains(com.northwalessolutions.netprobe.ui.tools.ScriptCopy.BAD_ID))
        assertTrue(list.contains(com.northwalessolutions.netprobe.ui.tools.ScriptCopy.BAD_TARGET))
        assertTrue(list.contains(com.northwalessolutions.netprobe.ui.tools.ScriptCopy.BAD_NETWORK))
        assertTrue(list.contains(com.northwalessolutions.netprobe.ui.tools.ScriptCopy.NO_RUN))
    }

    @Test fun `a script that does not run through reports why`() {
        assertTrue(errors(file("tool = {")).single().isNotBlank())
        assertEquals(listOf(com.northwalessolutions.netprobe.ui.tools.ScriptCopy.NO_TOOL_OBJECT), errors(file("var x = 1;")))
    }

    @Test fun `files that cannot be a script are refused with a reason`() {
        assertEquals(listOf(ImportCopy.EMPTY), errors(file("")))
        assertEquals(listOf(ImportCopy.FILE_NOT_TEXT), errors(runBlocking { importer().fromFile("b.js") { ByteArrayInputStream(byteArrayOf(0x74, 0xC3.toByte(), 0x28)) } }))
        assertEquals(listOf(ImportCopy.FILE_UNOPENED), errors(runBlocking { importer().fromFile("b.js") { null } }))
        assertEquals(listOf(ImportCopy.FILE_UNOPENED), errors(runBlocking { importer().fromFile("b.js") { throw IOException("gone") } }))
        assertEquals(listOf(ImportCopy.FILE_UNOPENED), errors(runBlocking { importer().fromFile("b.js") { throw SecurityException("no") } }))
        val big = "//" + "x".repeat(300 * 1024)
        assertEquals(listOf(com.northwalessolutions.netprobe.ui.tools.ScriptCopy.SOURCE_TOO_LARGE), errors(file(big)))
        val huge = ByteArray(ScriptBytes.MAX_IMPORT_BYTES + 1) { 'x'.code.toByte() }
        assertEquals(listOf(ImportCopy.TOO_LARGE), errors(runBlocking { importer().fromFile("h.js") { ByteArrayInputStream(huge) } }))
    }

    @Test fun `a file of exactly 1 MB is read but still limited by the script size cap`() {
        val exact = ByteArray(ScriptBytes.MAX_IMPORT_BYTES) { 'x'.code.toByte() }
        assertEquals(listOf(com.northwalessolutions.netprobe.ui.tools.ScriptCopy.SOURCE_TOO_LARGE), errors(runBlocking { importer().fromFile("h.js") { ByteArrayInputStream(exact) } }))
    }

    @Test fun `a byte order mark is dropped before the sha is taken`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + script().toByteArray()
        val plan = ready(runBlocking { importer().fromFile("b.js") { ByteArrayInputStream(bytes) } })
        assertEquals(ScriptBytes.sha256(script()), plan.sha256)
    }

    // URL

    private class Recording(private val body: ByteArray = ByteArray(0), private val result: UpdateFetch? = null, private val wait: Long = 0L) : UpdateFetcher {
        val urls = ArrayList<String>(); val caps = ArrayList<Long>()
        override suspend fun fetch(url: URL, maxBytes: Long, out: java.io.OutputStream, onProgress: (Long, Long?) -> Unit): UpdateFetch {
            urls += url.toString(); caps += maxBytes
            if (wait > 0) delay(wait)
            result?.let { return it }
            out.write(body)
            return UpdateFetch.Ok(body.size.toLong())
        }
    }

    private fun url(text: String, fetcher: UpdateFetcher, budget: Long = 20_000L) = runBlocking { importer(fetcher, budget).fromUrl(text) }

    @Test fun `an https address is fetched with a 1 MB cap and the plan remembers it`() {
        val f = Recording(script().toByteArray())
        val plan = ready(url("  https://example.org/tools/t.js#frag ", f))
        assertEquals(listOf("https://example.org/tools/t.js"), f.urls)
        assertEquals(listOf(1024L * 1024L), f.caps)
        assertEquals(listOf("https://example.org/tools/t.js", null), listOf(plan.sourceUrl, plan.fileName))
    }

    @Test fun `only plain https addresses on public hosts are fetched`() {
        val f = Recording(script().toByteArray())
        for (bad in listOf("http://example.org/t.js", "ftp://example.org/t.js", "file:///sdcard/t.js", "https://user:pw@example.org/t.js", "https://localhost/t.js", "https://printer/t.js", "https://192.168.1.5/t.js")) {
            assertEquals(bad, listOf(ImportCopy.URL_NOT_HTTPS), errors(url(bad, f)))
        }
        for (bad in listOf("", "   ", "not a url", "example.org/t.js", "https://exa mple.org/t.js", "https://example.org/" + "a".repeat(3000))) {
            assertEquals(bad, listOf(ImportCopy.URL_BAD), errors(url(bad, f)))
        }
        assertTrue(f.urls.isEmpty())
    }

    @Test fun `fetch failures read as their reasons`() {
        val cases = mapOf(FetchError.REFUSED to ImportCopy.FETCH_REFUSED, FetchError.TOO_MANY_REDIRECTS to ImportCopy.FETCH_REDIRECTS, FetchError.TOO_LARGE to ImportCopy.TOO_LARGE,
            FetchError.STATUS to ImportCopy.FETCH_STATUS, FetchError.CONNECTION to ImportCopy.FETCH_CONNECTION)
        for ((error, text) in cases) assertEquals(listOf(text), errors(url("https://example.org/t.js", Recording(result = UpdateFetch.Failed(error)))))
    }

    @Test fun `the whole fetch has a time budget`() {
        assertEquals(20_000L, ScriptImporter.URL_BUDGET_MS)
        assertEquals(listOf(ImportCopy.FETCH_TIMEOUT), errors(url("https://example.org/t.js", Recording(script().toByteArray(), wait = 5_000L), budget = 60L)))
    }

    // The real fetcher over fake connections: every hop must be https.

    private class Conn(url: URL, private val code: Int, private val location: String? = null, private val body: ByteArray = ByteArray(0)) : HttpURLConnection(url) {
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?): String? = if (name == "Location") location else null
        override fun getContentLengthLong() = body.size.toLong()
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }

    private fun realFetcher(opened: MutableList<String>, vararg routes: Pair<String, (URL) -> Conn>): UpdateFetcher {
        val map = routes.toMap()
        return HttpsUpdateFetcher { u -> opened += u.toString(); map.getValue(u.toString())(u) }
    }

    @Test fun `a redirect to http is refused and never requested`() {
        val opened = ArrayList<String>()
        val f = realFetcher(opened, "https://a.example/t.js" to { u -> Conn(u, 302, "http://b.example/t.js") })
        assertEquals(listOf(ImportCopy.FETCH_REFUSED), errors(url("https://a.example/t.js", f)))
        assertEquals(listOf("https://a.example/t.js"), opened)
    }

    @Test fun `a redirect chain of https hops is followed and a hop to a local name is refused`() {
        val opened = ArrayList<String>()
        val ok = realFetcher(opened, "https://a.example/t.js" to { u -> Conn(u, 301, "https://cdn.example/x/t.js") }, "https://cdn.example/x/t.js" to { u -> Conn(u, 200, body = script().toByteArray()) })
        ready(url("https://a.example/t.js", ok))
        assertEquals(listOf("https://a.example/t.js", "https://cdn.example/x/t.js"), opened)
        val local = ArrayList<String>()
        val bad = realFetcher(local, "https://a.example/t.js" to { u -> Conn(u, 302, "https://localhost/t.js") })
        assertEquals(listOf(ImportCopy.FETCH_REFUSED), errors(url("https://a.example/t.js", bad)))
        assertEquals(listOf("https://a.example/t.js"), local)
    }

    @Test fun `a body over 1 MB from the real fetcher is refused`() {
        val body = ByteArray(1024 * 1024 + 1) { 'x'.code.toByte() }
        val f = realFetcher(ArrayList(), "https://a.example/t.js" to { u -> Conn(u, 200, body = body) })
        assertEquals(listOf(ImportCopy.TOO_LARGE), errors(url("https://a.example/t.js", f)))
    }

    // replace rules

    @Test fun `versions compare piece by piece`() {
        val order = listOf("0.9", "1.0-beta", "1.0", "1.0.1", "1.2", "1.10", "2", "10")
        for (i in order.indices) for (j in order.indices) {
            val expected = i.compareTo(j).coerceIn(-1, 1)
            assertEquals("${order[i]} vs ${order[j]}", expected, ReplaceRules.compareVersions(order[i], order[j]).coerceIn(-1, 1))
        }
        assertEquals(0, ReplaceRules.compareVersions("1.0", "1.0.0"))
        assertEquals(0, ReplaceRules.compareVersions("V1", "v1"))
        assertTrue(ReplaceRules.isDowngrade("2.0", "1.9"))
        assertFalse(ReplaceRules.isDowngrade("1.0", "1.0"))
        assertFalse(ReplaceRules.isDowngrade("1.0", "1.0.0"))
        assertTrue(ReplaceRules.isDowngrade("1.10", "1.9"))
    }

    @Test fun `scope widens in the order target lan any`() {
        assertTrue(ReplaceRules.widens(NetworkScope.TARGET, NetworkScope.LAN))
        assertTrue(ReplaceRules.widens(NetworkScope.LAN, NetworkScope.ANY))
        assertTrue(ReplaceRules.widens(NetworkScope.TARGET, NetworkScope.ANY))
        assertFalse(ReplaceRules.widens(NetworkScope.ANY, NetworkScope.LAN))
        assertFalse(ReplaceRules.widens(NetworkScope.LAN, NetworkScope.LAN))
        assertFalse(ReplaceRules.widens(null, NetworkScope.ANY))
    }

    @Test fun `a replace shows the diff of scope inputs and sha`() {
        install(script(version = "1.0", network = "target", inputs = text("path") + "," + text("depth") + "," + text("mode")))
        val oldSha = store.entry("user:my-tool")!!.sha256
        val plan = ready(file(script(version = "1.1", network = "lan", inputs = text("path", "number") + "," + text("depth") + "," + text("extra"))))
        val r = plan.replace!!
        assertEquals(listOf("My tool", "1.0", "1.1"), listOf(r.oldName, r.oldVersion, r.newVersion))
        assertEquals(listOf(NetworkScope.TARGET, NetworkScope.LAN, true), listOf(r.scopeFrom, r.scopeTo, r.widened))
        assertEquals(listOf("Extra (text)"), r.inputsAdded)
        assertEquals(listOf("Mode (text)"), r.inputsRemoved)
        assertEquals(listOf("Path (number)"), r.inputsChanged)
        assertEquals(oldSha, r.shaFrom); assertEquals(plan.sha256, r.shaTo); assertFalse(r.identical)
        assertTrue(r.inputsKnown); assertTrue(plan.needsWideningAck)
    }

    @Test fun `widening needs the extra yes and narrowing does not`() {
        install(script(version = "1.0", network = "target"))
        val wide = ready(file(script(version = "1.1", network = "any")))
        assertTrue(wide.needsWideningAck)
        val refused = runBlocking { importer().confirm(wide, acknowledgedWidening = false) }
        assertEquals(ScriptImporter.ConfirmResult.Refused(ImportCopy.WIDENING_NEEDS_YES), refused)
        assertEquals("1.0", store.installed.value.single().version)
        assertEquals(ScriptImporter.ConfirmResult.Saved("My tool", true), runBlocking { importer().confirm(wide, acknowledgedWidening = true) })
        assertEquals(listOf("1.1", NetworkScope.ANY), store.installed.value.single().let { listOf(it.version, it.scope) })

        val narrow = ready(file(script(version = "1.2", network = "target")))
        assertFalse(narrow.needsWideningAck)
        assertEquals(ScriptImporter.ConfirmResult.Saved("My tool", true), runBlocking { importer().confirm(narrow, acknowledgedWidening = false) })
        assertEquals(NetworkScope.TARGET, store.installed.value.single().scope)
    }

    @Test fun `a first import for any address needs no extra yes but is called out by its scope`() {
        val plan = ready(file(script(network = "any")))
        assertFalse(plan.needsWideningAck)
        assertEquals(NetworkScope.ANY, plan.descriptor.network)
        assertEquals(ScriptImporter.ConfirmResult.Saved("My tool", false), runBlocking { importer().confirm(plan, acknowledgedWidening = false) })
    }

    @Test fun `a version downgrade is refused with the reason and nothing changes`() {
        install(script(version = "2.0"))
        val o = file(script(version = "1.9"))
        assertEquals(listOf(ImportCopy.downgrade("2.0", "1.9")), errors(o))
        assertEquals("2.0", store.installed.value.single().version)
        ready(file(script(version = "2.0")))
        ready(file(script(version = "2.1")))
    }

    @Test fun `the same script again shows as identical`() {
        install(script(version = "1.0"))
        val r = ready(file(script(version = "1.0"))).replace!!
        assertTrue(r.identical); assertFalse(r.widened)
    }

    @Test fun `a broken installed tool can be repaired even by an older version`() {
        install(script(version = "3.0"))
        File(root, "my-tool.js").writeText("tool = {")
        runBlocking { store.load() }
        assertTrue(store.installed.value.single().broken)
        val plan = ready(file(script(version = "1.0")))
        assertFalse(plan.replace!!.inputsKnown)
        assertEquals(ScriptImporter.ConfirmResult.Saved("My tool", true), runBlocking { importer().confirm(plan, acknowledgedWidening = false) })
        assertFalse(store.installed.value.single().broken)
    }

    @Test fun `a plan is checked again against what is installed when it is confirmed`() {
        install(script(version = "1.0"))
        val plan = ready(file(script(version = "1.1")))
        install(script(version = "1.0", network = "lan"))
        assertEquals(ScriptImporter.ConfirmResult.Refused(ImportCopy.STALE), runBlocking { importer().confirm(plan, true) })
        assertEquals("1.0", store.installed.value.single().version)
        install(script(version = "1.5"))
        assertEquals(ScriptImporter.ConfirmResult.Refused(ImportCopy.downgrade("1.5", "1.1")), runBlocking { importer().confirm(plan, true) })
    }

    @Test fun `confirm records where the script came from`() {
        val plan = ready(url("https://example.org/t.js", Recording(script().toByteArray())))
        runBlocking { importer().confirm(plan, false) }
        val e = store.entry("user:my-tool")!!
        assertEquals(listOf("https://example.org/t.js", null), listOf(e.sourceUrl, e.fileName))
        assertEquals(plan.source, File(root, "my-tool.js").readText())
    }

    @Test fun `the imported key can never be a built-in or an example key`() {
        val plan = ready(file(script(id = "dir-bruteforce")))
        assertEquals("user:dir-bruteforce", plan.descriptor.key)
        val example = ready(file(script(id = "http-headers")))
        assertEquals("user:http-headers", example.descriptor.key)
    }

    @Test fun `input kinds have the script names`() {
        assertEquals("text", InputKinds.of(InputSpec.Text("a", "A")))
        assertEquals("list", InputKinds.of(InputSpec.TextList("a", "A")))
        assertEquals("A (secret)", InputKinds.line(InputSpec.Secret("a", "A")))
        val kinds = listOf(InputSpec.Ip("a", "A"), InputSpec.Port("a", "A"), InputSpec.Text("a", "A"), InputSpec.Number("a", "A"), InputSpec.Bool("a", "A"),
            InputSpec.Choice("a", "A", emptyList()), InputSpec.Wordlist("a", "A"), InputSpec.TextList("a", "A"), InputSpec.MultiChoice("a", "A", emptyList()),
            InputSpec.Secret("a", "A"), InputSpec.Tls("a", "A")).map { InputKinds.of(it) }
        assertEquals(kinds.size, kinds.toSet().size)
    }
}
