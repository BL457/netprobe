package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import com.northwalessolutions.netprobe.ui.tools.CatalogCopy
import com.northwalessolutions.netprobe.ui.tools.ImportCopy
import com.northwalessolutions.netprobe.update.FetchError
import com.northwalessolutions.netprobe.update.UpdateFetch
import com.northwalessolutions.netprobe.update.UpdateFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.net.URL

/**
 * The catalog plan builder and apply step against a real store and the real import pipeline, with a fake fetcher: no test here touches the
 * network, and the real index URL is never fetched (spec section 11).
 */
class CatalogUpdaterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val env = NetworkEnv(Ipv4.parse("192.168.1.50")!!, 24, null, emptyList(), null, null, Transport.WIFI)
    private val engine = RhinoScriptEngine()
    private val store: ScriptToolStore by lazy {
        ScriptToolStore(ScriptAssets { null }, engine, { d, src -> ScriptToolModule(d, src, engine, { env }, { null }) }, {}, File(tmp.root, "tools"), { 7L })
    }
    private val base = "https://catalog.test/c/"

    /** URL to body or error; records every request. */
    private class MapFetcher : UpdateFetcher {
        val files = HashMap<String, ByteArray>()
        val errors = HashMap<String, FetchError>()
        val requested = ArrayList<String>()
        override suspend fun fetch(url: URL, maxBytes: Long, out: OutputStream, onProgress: (Long, Long?) -> Unit): UpdateFetch {
            requested += url.toString()
            errors[url.toString()]?.let { return UpdateFetch.Failed(it) }
            val body = files[url.toString()] ?: return UpdateFetch.Failed(FetchError.STATUS)
            check(body.size <= maxBytes) { "fake served more than the cap" }
            out.write(body)
            return UpdateFetch.Ok(body.size.toLong())
        }
    }
    private val fetcher = MapFetcher()
    private val importer by lazy { ScriptImporter(engine, store, fetcher) }
    private val updater by lazy { CatalogUpdater(importer, store, fetcher, base + "index.json", fetchBudgetMs = 5_000) }

    private fun script(id: String = "tool-a", version: String = "1.0", network: String = "target", broadcast: Boolean = false, name: String = "Tool $id") =
        """tool = { id: "$id", name: "$name", version: "$version", target: "none", network: "$network", ${if (broadcast) "broadcast: true," else ""} run: function () {} };"""

    /** Publishes [scripts] (file id to source) and an index that describes them, with optional per-id overrides of the index line. */
    private fun publish(scripts: Map<String, String>, override: Map<String, (String) -> String> = emptyMap(), rawIndex: String? = null) {
        fetcher.files.clear()
        val lines = scripts.map { (id, source) ->
            fetcher.files[base + "$id.js"] = source.toByteArray()
            val line = """{"id":"$id","file":"$id.js","name":"Tool $id","version":"1.0","sha256":"${ScriptBytes.sha256(source)}","network":"target","description":"d","broadcast":false}"""
            override[id]?.invoke(line) ?: line
        }
        fetcher.files[base + "index.json"] = (rawIndex ?: """{"format":1,"tools":[${lines.joinToString(",")}]}""").toByteArray()
        fetcher.requested.clear()
    }

    private fun check(): CatalogPlan = (runBlocking { updater.check() } as? CatalogOutcome.Ready)?.plan ?: error("index not ready")
    private fun apply(plan: CatalogPlan, yes: Boolean = false) = runBlocking { updater.apply(plan, yes) }
    private fun installAll(scripts: Map<String, String>) { publish(scripts); val applied = apply(check(), yes = true); assertTrue(applied.failed.toString(), applied.failed.isEmpty()) }
    private fun installLocal(source: String) {
        val plan = (runBlocking { importer.plan(source.toByteArray(), null, "x.js") } as ImportOutcome.Ready).plan
        assertTrue(runBlocking { importer.confirm(plan, true) } is ScriptImporter.ConfirmResult.Saved)
    }
    private fun names(items: List<CatalogItem>) = items.map { it.entry.id }

    // New

    @Test fun `a tool that is not installed is New and only the index and that script are fetched`() {
        publish(mapOf("tool-a" to script("tool-a")))
        val plan = check()
        assertEquals(listOf("tool-a"), names(plan.new))
        assertTrue(plan.updated.isEmpty() && plan.failed.isEmpty()); assertEquals(0, plan.unchanged)
        assertEquals(listOf(base + "index.json", base + "tool-a.js"), fetcher.requested)
        val item = plan.new.single()
        assertTrue(item.isNew); assertEquals("Tool tool-a", item.descriptor.name); assertEquals("1.0", item.descriptor.version)
        assertTrue(item.descriptor.source is ToolSource.Catalog)
        assertFalse(plan.needsYes)
    }

    @Test fun `building a plan saves nothing and a plan alone fetches nothing`() {
        assertTrue(fetcher.requested.isEmpty())
        publish(mapOf("tool-a" to script("tool-a")))
        check()
        assertTrue(store.installed.value.isEmpty())
        assertFalse(File(tmp.root, "tools").exists())
    }

    @Test fun `a new tool shows its scope and its broadcast flag`() {
        publish(mapOf("lan-one" to script("lan-one", network = "lan"), "bcast" to script("bcast", network = "lan", broadcast = true), "any-one" to script("any-one", network = "any")))
        val byId = check().new.associateBy { it.entry.id }
        assertEquals(NetworkScope.LAN, byId.getValue("lan-one").descriptor.network); assertFalse(byId.getValue("lan-one").descriptor.broadcast)
        assertTrue(byId.getValue("bcast").descriptor.broadcast)
        assertEquals(NetworkScope.ANY, byId.getValue("any-one").descriptor.network)
        assertFalse("a new tool needs no extra yes", check().needsYes)
    }

    // Unchanged

    @Test fun `an installed tool with the same sha is counted and not fetched again`() {
        installAll(mapOf("tool-a" to script("tool-a"), "tool-b" to script("tool-b")))
        publish(mapOf("tool-a" to script("tool-a"), "tool-b" to script("tool-b"), "tool-c" to script("tool-c")))
        val plan = check()
        assertEquals(2, plan.unchanged); assertEquals(listOf("tool-c"), names(plan.new))
        assertEquals(listOf(base + "index.json", base + "tool-c.js"), fetcher.requested)
    }

    @Test fun `a broken installed copy with the same sha is repaired, not skipped`() {
        installAll(mapOf("tool-a" to script("tool-a")))
        File(tmp.root, "tools/tool-a.js").delete()
        runBlocking { store.load() }
        assertNotNull(store.entry("user:tool-a")); assertNull(store.moduleOf("user:tool-a"))
        publish(mapOf("tool-a" to script("tool-a")))
        val plan = check()
        assertEquals(0, plan.unchanged); assertEquals(listOf("tool-a"), names(plan.updated))
    }

    // Updated

    @Test fun `a different sha is Updated with the old and new version`() {
        installAll(mapOf("tool-a" to script("tool-a", "1.0")))
        publish(mapOf("tool-a" to script("tool-a", "1.1")))
        val plan = check()
        val item = plan.updated.single()
        assertEquals("1.0", item.oldVersion); assertEquals("1.1", item.descriptor.version)
        assertFalse(item.isNew); assertFalse(item.widened); assertFalse(item.broadcastNew); assertFalse(item.replacesLocal)
        assertTrue(plan.new.isEmpty() && plan.failed.isEmpty()); assertFalse(plan.needsYes)
    }

    @Test fun `the same version with a changed script is allowed to update`() {
        installAll(mapOf("tool-a" to script("tool-a", "1.0", name = "Old name")))
        publish(mapOf("tool-a" to script("tool-a", "1.0", name = "New name")))
        assertEquals(listOf("tool-a"), names(check().updated))
    }

    @Test fun `a scope that widens is flagged and needs the extra yes`() {
        for ((id, change) in listOf("tool-a" to ("target" to "lan"), "tool-b" to ("lan" to "any"), "tool-c" to ("target" to "any"))) {
            installAll(mapOf(id to script(id, "1.0", network = change.first)))
            publish(mapOf(id to script(id, "1.1", network = change.second)))
            val plan = check()
            val item = plan.updated.single()
            assertTrue("$change", item.widened); assertEquals(NetworkScope.valueOf(change.first.uppercase()), item.scopeFrom); assertTrue(plan.needsYes)
        }
    }

    @Test fun `a scope that narrows or stays is not flagged`() {
        installAll(mapOf("tool-a" to script("tool-a", "1.0", network = "lan")))
        publish(mapOf("tool-a" to script("tool-a", "1.1", network = "target")))
        assertFalse(check().updated.single().widened)
        publish(mapOf("tool-a" to script("tool-a", "1.1", network = "lan")))
        assertFalse(check().updated.single().widened)
    }

    @Test fun `a widening update is not installed without the yes and is installed with it`() {
        installAll(mapOf("tool-a" to script("tool-a", "1.0", network = "target")))
        publish(mapOf("tool-a" to script("tool-a", "1.1", network = "any")))
        val plan = check()
        val refused = apply(plan, yes = false)
        assertTrue(refused.installed.isEmpty()); assertEquals(listOf(ImportCopy.WIDENING_NEEDS_YES), refused.failed.map { it.reason })
        assertEquals("1.0", store.moduleOf("user:tool-a")!!.descriptor.version)
        val saved = apply(plan, yes = true)
        assertEquals(listOf("Tool tool-a"), saved.installed)
        assertEquals("1.1", store.moduleOf("user:tool-a")!!.descriptor.version)
        assertEquals(NetworkScope.ANY, store.moduleOf("user:tool-a")!!.descriptor.network)
    }

    @Test fun `a broadcast newly declared is flagged, and one already declared is not`() {
        installAll(mapOf("tool-a" to script("tool-a", "1.0", network = "lan")))
        publish(mapOf("tool-a" to script("tool-a", "1.1", network = "lan", broadcast = true)))
        val item = check().updated.single()
        assertTrue(item.broadcastNew); assertFalse(item.widened); assertTrue(item.needsYes)
        assertTrue(check().needsYes)
        apply(check(), yes = true)
        publish(mapOf("tool-a" to script("tool-a", "1.2", network = "lan", broadcast = true)))
        val again = check().updated.single()
        assertFalse(again.broadcastNew); assertFalse(check().needsYes)
    }

    @Test fun `a broadcast update is not installed without the yes`() {
        installAll(mapOf("tool-a" to script("tool-a", "1.0", network = "lan")))
        publish(mapOf("tool-a" to script("tool-a", "1.1", network = "lan", broadcast = true)))
        val refused = apply(check(), yes = false)
        assertTrue(refused.installed.isEmpty()); assertFalse(store.moduleOf("user:tool-a")!!.descriptor.broadcast)
    }

    // Downgrade

    @Test fun `a downgrade is refused and listed under Failed`() {
        installAll(mapOf("tool-a" to script("tool-a", "2.0")))
        publish(mapOf("tool-a" to script("tool-a", "1.0", name = "Older")))
        val plan = check()
        assertTrue(plan.updated.isEmpty() && plan.new.isEmpty())
        assertEquals(listOf(CatalogFailure("Tool tool-a", ImportCopy.downgrade("2.0", "1.0"))), plan.failed)
        assertTrue(apply(plan, true).installed.isEmpty())
        assertEquals("2.0", store.moduleOf("user:tool-a")!!.descriptor.version)
    }

    // Local copies

    @Test fun `a locally imported tool with the same id is replaced and says so`() {
        installLocal(script("tool-a", "1.0", name = "My own copy"))
        publish(mapOf("tool-a" to script("tool-a", "1.1")))
        val item = check().updated.single()
        assertTrue(item.replacesLocal); assertEquals("1.0", item.oldVersion)
        apply(check(), yes = true)
        assertTrue(store.moduleOf("user:tool-a")!!.descriptor.source is ToolSource.Catalog)
        publish(mapOf("tool-a" to script("tool-a", "1.2")))
        assertFalse("now a catalog copy", check().updated.single().replacesLocal)
    }

    @Test fun `a local copy newer than the catalog is a downgrade and is kept`() {
        installLocal(script("tool-a", "3.0", name = "My own copy"))
        publish(mapOf("tool-a" to script("tool-a", "1.0")))
        val plan = check()
        assertEquals(1, plan.failed.size); assertTrue(plan.failed.single().reason.startsWith("Version 1.0 is older"))
    }

    // Removed from the catalog

    @Test fun `a tool removed from the catalog stays installed and is never mentioned`() {
        installAll(mapOf("tool-a" to script("tool-a"), "tool-b" to script("tool-b")))
        publish(mapOf("tool-a" to script("tool-a")))
        val plan = check()
        assertEquals(1, plan.unchanged); assertTrue(plan.new.isEmpty() && plan.updated.isEmpty() && plan.failed.isEmpty())
        assertEquals(listOf(base + "index.json"), fetcher.requested)
        apply(plan, true)
        assertNotNull(store.moduleOf("user:tool-b"))
    }

    @Test fun `a hand imported tool that the catalog does not list is untouched`() {
        installLocal(script("mine-only", "1.0"))
        publish(mapOf("tool-a" to script("tool-a")))
        apply(check(), true)
        assertEquals(setOf("user:mine-only", "user:tool-a"), store.installed.value.map { it.key }.toSet())
    }

    // Failures

    @Test fun `a script that cannot be fetched is Failed and the others still install`() {
        publish(mapOf("tool-a" to script("tool-a"), "tool-b" to script("tool-b")))
        fetcher.errors[base + "tool-a.js"] = FetchError.CONNECTION
        val plan = check()
        assertEquals(listOf(CatalogFailure("Tool tool-a", ImportCopy.FETCH_CONNECTION)), plan.failed)
        assertEquals(listOf("tool-b"), names(plan.new))
        assertEquals(listOf("Tool tool-b"), apply(plan, true).installed)
        assertNull(store.entry("user:tool-a"))
    }

    @Test fun `every fetch error has its own reason`() {
        val expected = mapOf(FetchError.REFUSED to ImportCopy.FETCH_REFUSED, FetchError.TOO_MANY_REDIRECTS to ImportCopy.FETCH_REDIRECTS, FetchError.TOO_LARGE to ImportCopy.TOO_LARGE,
            FetchError.STATUS to ImportCopy.FETCH_STATUS, FetchError.CONNECTION to ImportCopy.FETCH_CONNECTION)
        for ((error, reason) in expected) {
            publish(mapOf("tool-a" to script("tool-a")))
            fetcher.errors[base + "tool-a.js"] = error
            assertEquals(error.name, reason, check().failed.single().reason)
            fetcher.errors.clear(); fetcher.errors[base + "index.json"] = error
            assertEquals(error.name, CatalogOutcome.Failed(reason), runBlocking { updater.check() })
            fetcher.errors.clear()
        }
    }

    @Test fun `a script that does not match the index sha is Failed and nothing from it is installed`() {
        publish(mapOf("tool-a" to script("tool-a")), override = mapOf("tool-a" to { line -> line.replace(ScriptBytes.sha256(script("tool-a")), "0".repeat(64)) }))
        val plan = check()
        assertEquals(listOf(CatalogFailure("Tool tool-a", CatalogCopy.SCRIPT_MISMATCH)), plan.failed)
        assertTrue(plan.new.isEmpty())
    }

    @Test fun `a script whose id is not the listed id is Failed`() {
        val source = script("other-tool")
        publish(mapOf("tool-a" to source))
        assertEquals(listOf(CatalogFailure("Tool tool-a", CatalogCopy.ID_MISMATCH)), check().failed)
    }

    @Test fun `a script with a bad description is Failed with the parser's reason`() {
        publish(mapOf("tool-a" to """tool = { id: "tool-a", name: "Bad", target: "sideways", run: function () {} };"""))
        val failed = check().failed.single()
        assertEquals("Tool tool-a", failed.name); assertEquals("The target must be port, host or none", failed.reason)
    }

    @Test fun `a script that does not load is Failed`() {
        publish(mapOf("tool-a" to "tool = {"))
        assertTrue(check().failed.single().reason.isNotBlank())
    }

    @Test fun `a script that is declared broadcast with a wider network is rejected by the descriptor rules`() {
        publish(mapOf("tool-a" to """tool = { id: "tool-a", name: "T", target: "none", network: "any", broadcast: true, run: function () {} };"""))
        assertEquals("A tool can declare broadcast only with the network set to lan", check().failed.single().reason)
    }

    @Test fun `bad lines of the index are listed under Failed and the good ones are kept`() {
        val good = script("tool-a")
        publish(mapOf("tool-a" to good), rawIndex = """{"format":1,"tools":[{"id":"BAD","file":"x"},{"id":"tool-a","file":"tool-a.js","name":"Tool tool-a","sha256":"${ScriptBytes.sha256(good)}"}]}""")
        val plan = check()
        assertEquals(listOf("tool-a"), names(plan.new))
        assertEquals(listOf(CatalogFailure("BAD", CatalogCopy.INDEX_FIELDS)), plan.failed)
    }

    @Test fun `an unreadable or missing index is a failure of the whole check`() {
        for (raw in listOf("", "not json", """{"format":9,"tools":[]}""")) {
            publish(emptyMap(), rawIndex = raw)
            assertEquals(raw, CatalogOutcome.Failed(CatalogCopy.INDEX_UNREADABLE), runBlocking { updater.check() })
        }
        fetcher.files.clear()
        assertEquals(CatalogOutcome.Failed(ImportCopy.FETCH_STATUS), runBlocking { updater.check() })
        publish(emptyMap()); fetcher.files[base + "index.json"] = byteArrayOf(-1, -2, -3)
        assertEquals(CatalogOutcome.Failed(CatalogCopy.INDEX_UNREADABLE), runBlocking { updater.check() })
    }

    @Test fun `an empty catalog is ready with nothing in it`() {
        publish(emptyMap())
        val plan = check()
        assertEquals(CatalogPlan(emptyList(), emptyList(), emptyList(), 0), plan)
        assertEquals(0, plan.installable)
    }

    @Test fun `a script is fetched from the folder of the index and from nowhere else`() {
        publish(mapOf("tool-a" to script("tool-a")))
        check()
        assertTrue(fetcher.requested.all { it.startsWith(base) })
    }

    // Apply

    @Test fun `apply installs every New and Updated tool from the catalog and marks them as catalog tools`() {
        installAll(mapOf("tool-a" to script("tool-a", "1.0")))
        publish(mapOf("tool-a" to script("tool-a", "1.1"), "tool-b" to script("tool-b")))
        val applied = apply(check(), yes = false)
        assertEquals(setOf("Tool tool-a", "Tool tool-b"), applied.installed.toSet()); assertTrue(applied.failed.isEmpty())
        for (key in listOf("user:tool-a", "user:tool-b")) {
            assertTrue(key, store.moduleOf(key)!!.descriptor.source is ToolSource.Catalog)
            assertTrue(key, store.entry(key)!!.catalog)
            assertEquals(base + key.removePrefix("user:") + ".js", store.entry(key)!!.sourceUrl)
        }
        assertEquals("1.1", store.moduleOf("user:tool-a")!!.descriptor.version)
    }

    @Test fun `a catalog tool is still a catalog tool after the store is loaded again`() {
        installAll(mapOf("tool-a" to script("tool-a")))
        runBlocking { store.load() }
        assertTrue(store.moduleOf("user:tool-a")!!.descriptor.source is ToolSource.Catalog)
        assertTrue(store.installed.value.single().catalog)
    }

    @Test fun `a plan that went stale is not applied`() {
        installAll(mapOf("tool-a" to script("tool-a", "1.0")))
        publish(mapOf("tool-a" to script("tool-a", "1.1")))
        val plan = check()
        installLocal(script("tool-a", "1.0", name = "Changed meanwhile"))
        val applied = apply(plan, true)
        assertTrue(applied.installed.isEmpty()); assertEquals(listOf(ImportCopy.STALE), applied.failed.map { it.reason })
    }

    @Test fun `progress is reported once the index is read and after each line`() {
        installAll(mapOf("tool-a" to script("tool-a")))
        publish(mapOf("tool-a" to script("tool-a"), "tool-b" to script("tool-b"), "tool-c" to script("tool-c")))
        val seen = ArrayList<Pair<Int, Int>>()
        runBlocking { updater.check { done, total -> seen += done to total } }
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), seen)
    }

    @Test fun `an index with more than 200 lines checks only the first 200`() {
        val scripts = (1..205).associate { "tool-%03d".format(it) to script("tool-%03d".format(it)) }
        publish(scripts)
        val plan = check()
        assertEquals(200, plan.new.size)
        assertTrue(plan.failed.any { it.reason == CatalogCopy.INDEX_TOO_LONG })
    }
}
