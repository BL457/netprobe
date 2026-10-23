package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import com.northwalessolutions.netprobe.engine.tools.ToolDescriptor
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import com.northwalessolutions.netprobe.ui.tools.ImportCopy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The real store over a temporary files/tools folder: save, replace, remove, load on start, and every kind of broken entry. */
class ScriptToolStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val env = NetworkEnv(Ipv4.parse("192.168.1.50")!!, 24, null, emptyList(), null, null, Transport.WIFI)
    private val engine = RhinoScriptEngine()
    private val log = ArrayList<String>()
    private val root: File by lazy { File(tmp.root, "tools") }
    private var now = 1_000L

    private fun store(assets: ScriptAssets = ScriptAssets { null }, dir: File? = root) = ScriptToolStore(
        assets, engine, { d, src -> ScriptToolModule(d, src, engine, { env }, { null }) }, { log += it }, dir, { now })

    private fun script(id: String = "my-tool", name: String = "My tool", version: String = "1.0", network: String = "target") =
        """tool = { id: "$id", name: "$name", version: "$version", target: "none", network: "$network", inputs: [], run: function (inputs, ctx) { return { status: "done" }; } };"""

    private fun descriptor(source: String): ToolDescriptor = runBlocking {
        val loaded = engine.evaluate(source) as ScriptEvaluation.Loaded
        (ScriptDescriptorParser.parse(loaded.tool, loaded.hasRun, ToolKeys.USER_PREFIX, null, ToolSource.Script(File("x"), "")) as ParsedTool.Valid).descriptor
    }

    private fun save(s: ScriptToolStore, source: String, url: String? = null, file: String? = "tool.js"): SaveResult = runBlocking { s.save(descriptor(source), source, url, file) }

    /** Writes a script file and an index line by hand, with the right sha unless [sha] says otherwise. */
    private fun plant(slug: String, source: String, key: String = "user:$slug", sha: String? = null, name: String = "Planted", write: Boolean = true) {
        root.mkdirs()
        if (write) File(root, "$slug.js").writeText(source)
        val existing = File(root, "index.json").takeIf { it.isFile }?.let { Json.parseToJsonElement(it.readText()).jsonObject["tools"]!!.jsonArray.map { e -> e.toString() } }.orEmpty()
        val line = """{"key":"$key","name":"$name","version":"1.0","sha256":"${sha ?: ScriptBytes.sha256(source)}","scope":"target","importedAt":5,"fileName":"p.js"}"""
        File(root, "index.json").writeText("""{"format":1,"tools":[${(existing + line).joinToString(",")}]}""")
    }

    @Test fun `a saved tool is written under files tools with its index line`() {
        val s = store()
        val source = script(network = "lan")
        assertEquals(SaveResult.Saved(replaced = false), save(s, source, url = "https://example.org/t.js", file = null))
        assertEquals(source, File(root, "my-tool.js").readText())
        val index = Json.parseToJsonElement(File(root, "index.json").readText()).jsonObject["tools"]!!.jsonArray
        assertEquals(1, index.size)
        val line = index[0].jsonObject
        assertEquals("user:my-tool", line["key"]!!.jsonPrimitive.content)
        assertEquals("My tool", line["name"]!!.jsonPrimitive.content)
        assertEquals("1.0", line["version"]!!.jsonPrimitive.content)
        assertEquals(ScriptBytes.sha256(source), line["sha256"]!!.jsonPrimitive.content)
        assertEquals("lan", line["scope"]!!.jsonPrimitive.content)
        assertEquals(1_000L, line["importedAt"]!!.jsonPrimitive.content.toLong())
        assertEquals("https://example.org/t.js", line["sourceUrl"]!!.jsonPrimitive.content)
        assertFalse(File(root, "my-tool.js.part").exists())
        assertFalse(File(root, "index.json.part").exists())
    }

    @Test fun `a tool saved in one run loads in the next`() {
        save(store(), script(), file = "mine.js")
        val again = store()
        runBlocking { again.load() }
        val module = again.modules.value.single()
        assertEquals("user:my-tool", module.descriptor.key)
        assertEquals(ToolCategory.MINE, module.descriptor.category)
        val source = module.descriptor.source as ToolSource.Script
        assertEquals(File(root, "my-tool.js"), source.file)
        assertEquals(ScriptBytes.sha256(script()), source.sha256)
        val row = again.installed.value.single()
        assertEquals(InstalledTool("user:my-tool", "My tool", "1.0", NetworkScope.TARGET, 1_000L, ScriptBytes.sha256(script()), null, "mine.js", null), row)
    }

    @Test fun `save replace and remove change both flows`() {
        val s = store()
        assertEquals(SaveResult.Saved(false), save(s, script(version = "1.0")))
        assertEquals(listOf("1.0"), s.installed.value.map { it.version })
        now = 2_000L
        assertEquals(SaveResult.Saved(true), save(s, script(version = "1.1", name = "Renamed", network = "any")))
        assertEquals(1, s.modules.value.size)
        val row = s.installed.value.single()
        assertEquals(listOf("Renamed", "1.1", NetworkScope.ANY, 2_000L), listOf(row.name, row.version, row.scope, row.importedAt))
        assertEquals("Renamed", s.modules.value.single().descriptor.name)
        assertTrue(runBlocking { s.remove("user:my-tool") })
        assertTrue(s.modules.value.isEmpty())
        assertTrue(s.installed.value.isEmpty())
        assertFalse(File(root, "my-tool.js").exists())
        val again = store(); runBlocking { again.load() }
        assertTrue(again.installed.value.isEmpty())
    }

    @Test fun `removing an unknown tool does nothing`() {
        val s = store(); save(s, script())
        assertFalse(runBlocking { s.remove("user:other-tool") })
        assertFalse(runBlocking { s.remove("example:http-headers") })
        assertEquals(1, s.installed.value.size)
    }

    @Test fun `bundled examples keep loading under the example prefix beside imported tools`() {
        val assets = ScriptAssets { name -> File("src/main/assets/tools", name).takeIf { it.isFile }?.readText() }
        val s = store(assets)
        save(s, script())
        val fresh = store(assets)
        runBlocking { fresh.load() }
        assertEquals(setOf("example:http-headers", "example:banner-grab", "example:dns-lookup", "user:my-tool"), fresh.modules.value.map { it.descriptor.key }.toSet())
        assertEquals(listOf("user:my-tool"), fresh.installed.value.map { it.key })
    }

    @Test fun `a missing script file is listed as broken and not offered`() {
        plant("lost-tool", "x", write = false)
        val s = store(); runBlocking { s.load() }
        assertTrue(s.modules.value.isEmpty())
        val row = s.installed.value.single()
        assertEquals(ImportCopy.FILE_MISSING, row.error)
        assertTrue(row.broken)
        assertEquals("Planted", row.name)
    }

    @Test fun `a script that no longer matches its sha is broken`() {
        val source = script()
        plant("my-tool", source.replace("1.0", "9.9"), sha = ScriptBytes.sha256(source))
        val s = store(); runBlocking { s.load() }
        assertEquals(ImportCopy.FILE_CHANGED, s.installed.value.single().error)
        assertTrue(s.modules.value.isEmpty())
    }

    @Test fun `a script that does not parse is broken with the reason`() {
        plant("bad-syntax", "tool = {")
        plant("no-run-fn", """tool = { id: "no-run-fn", name: "x", target: "none" };""")
        val s = store(); runBlocking { s.load() }
        val rows = s.installed.value.associateBy { it.key }
        assertTrue(rows.getValue("user:bad-syntax").error!!.startsWith("Cannot load: "))
        assertTrue(rows.getValue("user:no-run-fn").error!!.contains("run function"))
        assertTrue(s.modules.value.isEmpty())
    }

    @Test fun `a script that is not utf-8 text is broken`() {
        root.mkdirs()
        val bytes = byteArrayOf(0x74, 0x6f, 0xC3.toByte(), 0x28)
        File(root, "binary-one.js").writeBytes(bytes)
        File(root, "index.json").writeText("""{"tools":[{"key":"user:binary-one","name":"Binary","version":"1","sha256":"${ScriptBytes.sha256(bytes)}","scope":"target","importedAt":1}]}""")
        val s = store(); runBlocking { s.load() }
        assertEquals(ImportCopy.FILE_NOT_TEXT, s.installed.value.single().error)
    }

    @Test fun `a script whose id no longer matches its key is broken`() {
        plant("first-name", script(id = "second-name"))
        val s = store(); runBlocking { s.load() }
        assertEquals(ImportCopy.KEY_MISMATCH, s.installed.value.single().error)
    }

    @Test fun `a broken entry never hides the working ones and can be removed`() {
        plant("good-tool", script(id = "good-tool", name = "Good"))
        plant("lost-tool", "x", write = false)
        val s = store(); runBlocking { s.load() }
        assertEquals(listOf("user:good-tool"), s.modules.value.map { it.descriptor.key })
        assertEquals(listOf("user:good-tool", "user:lost-tool"), s.installed.value.map { it.key })
        assertTrue(runBlocking { s.remove("user:lost-tool") })
        assertEquals(listOf("user:good-tool"), s.installed.value.map { it.key })
        val index = Json.parseToJsonElement(File(root, "index.json").readText()).jsonObject["tools"]!!.jsonArray
        assertEquals(listOf("user:good-tool"), index.map { it.jsonObject["key"]!!.jsonPrimitive.content })
    }

    @Test fun `a replace repairs a broken entry`() {
        plant("my-tool", "tool = {")
        val s = store(); runBlocking { s.load() }
        assertTrue(s.installed.value.single().broken)
        assertEquals(SaveResult.Saved(true), save(s, script()))
        assertFalse(s.installed.value.single().broken)
        assertEquals(1, s.modules.value.size)
    }

    @Test fun `a script file the list does not know is listed and removable but never loaded`() {
        root.mkdirs()
        File(root, "stray-one.js").writeText(script(id = "stray-one"))
        File(root, "Not A Slug.js").writeText("x")
        val s = store(); runBlocking { s.load() }
        assertTrue(s.modules.value.isEmpty())
        val row = s.installed.value.single()
        assertEquals(listOf("user:stray-one", ImportCopy.NOT_IN_LIST), listOf(row.key, row.error))
        assertTrue(runBlocking { s.remove("user:stray-one") })
        assertFalse(File(root, "stray-one.js").exists())
        assertTrue(File(root, "Not A Slug.js").exists())
    }

    @Test fun `an unreadable list names its scripts as broken`() {
        root.mkdirs()
        File(root, "index.json").writeText("{ not json")
        File(root, "kept-one.js").writeText(script(id = "kept-one"))
        val s = store(); runBlocking { s.load() }
        assertEquals(ImportCopy.LIST_UNREADABLE, s.installed.value.single().error)
        assertTrue(s.modules.value.isEmpty())
    }

    @Test fun `a key that is not a plain slug never builds a path`() {
        val outside = File(tmp.root, "evil.js").apply { writeText("x") }
        root.mkdirs()
        File(root, "index.json").writeText("""{"tools":[{"key":"user:../evil","name":"Evil","version":"1","sha256":"00","scope":"target","importedAt":1}]}""")
        val s = store(); runBlocking { s.load() }
        assertTrue(s.installed.value.isEmpty())
        assertFalse(runBlocking { s.remove("user:../evil") })
        assertNull(s.fileFor("user:../evil"))
        assertNull(s.fileFor("example:http-headers"))
        assertNull(s.fileFor("dir-bruteforce"))
        assertNull(s.fileFor("user:ab"))
        assertEquals(File(root, "my-tool.js"), s.fileFor("user:my-tool"))
        assertTrue(outside.exists())
    }

    @Test fun `a save that cannot write fails and leaves the old tool`() {
        val s = store()
        assertEquals(SaveResult.Saved(false), save(s, script(version = "1.0")))
        val blocker = File(root, "index.json.part"); blocker.mkdirs(); File(blocker, "x").writeText("x")   // a folder where the temporary index goes
        val result = save(s, script(version = "2.0"))
        assertEquals(SaveResult.Failed(ImportCopy.SAVE_FAILED), result)
        assertEquals(script(version = "1.0"), File(root, "my-tool.js").readText())
        assertEquals("1.0", s.installed.value.single().version)
        blocker.deleteRecursively()
        val again = store(); runBlocking { again.load() }
        assertFalse(again.installed.value.single().broken)
    }

    @Test fun `a store with no folder saves nothing`() {
        val s = store(dir = null)
        assertEquals(SaveResult.Failed(ImportCopy.SAVE_FAILED), save(s, script()))
        assertFalse(runBlocking { s.remove("user:my-tool") })
        runBlocking { s.load() }
        assertTrue(s.installed.value.isEmpty())
    }

    @Test fun `the index entry and the working module are found by key`() {
        val s = store(); save(s, script(network = "any"), file = "any.js")
        val e = s.entry("user:my-tool")
        assertNotNull(e)
        assertEquals(listOf("any", "any.js"), listOf(e!!.scope, e.fileName))
        assertNull(s.entry("user:nothing"))
        assertNotNull(s.moduleOf("user:my-tool"))
        assertNull(s.moduleOf("user:nothing"))
    }
}
