package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.script.CatalogUpdater
import com.northwalessolutions.netprobe.engine.script.RhinoScriptEngine
import com.northwalessolutions.netprobe.engine.script.ScriptAssets
import com.northwalessolutions.netprobe.engine.script.ScriptBytes
import com.northwalessolutions.netprobe.engine.script.ScriptImporter
import com.northwalessolutions.netprobe.engine.script.ScriptToolModule
import com.northwalessolutions.netprobe.engine.script.ScriptToolStore
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import com.northwalessolutions.netprobe.update.FetchError
import com.northwalessolutions.netprobe.update.UpdateFetch
import com.northwalessolutions.netprobe.update.UpdateFetcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
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

/** The catalog action's state machine with a fake fetcher: working, review, failed, cancel, the extra yes, install and the notice. */
class CatalogControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val env = NetworkEnv(Ipv4.parse("192.168.1.50")!!, 24, null, emptyList(), null, null, Transport.WIFI)
    private val engine = RhinoScriptEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logged = ArrayList<Throwable>()
    private val store by lazy { ScriptToolStore(ScriptAssets { null }, engine, { d, s -> ScriptToolModule(d, s, engine, { env }, { null }) }, {}, File(tmp.root, "tools")) }
    private val base = "https://catalog.test/c/"
    private val files = HashMap<String, ByteArray>()
    private var failIndex: FetchError? = null
    private var gate: CompletableDeferred<Unit>? = null
    private val requested = ArrayList<String>()
    private val fetcher = object : UpdateFetcher {
        override suspend fun fetch(url: URL, maxBytes: Long, out: OutputStream, onProgress: (Long, Long?) -> Unit): UpdateFetch {
            requested += url.toString()
            gate?.await()
            if (url.toString().endsWith("index.json")) failIndex?.let { return UpdateFetch.Failed(it) }
            val body = files[url.toString()] ?: return UpdateFetch.Failed(FetchError.STATUS)
            out.write(body)
            return UpdateFetch.Ok(body.size.toLong())
        }
    }
    private val controller by lazy {
        CatalogController(scope, CatalogUpdater(ScriptImporter(engine, store, fetcher), store, fetcher, base + "index.json", fetchBudgetMs = 5_000), { logged.add(it) })
    }

    @After fun stop() { scope.coroutineContext[Job]?.cancel() }

    private fun script(id: String, version: String = "1.0", network: String = "target") =
        """tool = { id: "$id", name: "Tool $id", version: "$version", target: "none", network: "$network", run: function () {} };"""

    private fun publish(vararg scripts: String) {
        files.clear()
        val lines = scripts.map { src ->
            val id = Regex("id: \"([a-z-]+)\"").find(src)!!.groupValues[1]
            val network = Regex("network: \"([a-z]+)\"").find(src)!!.groupValues[1]
            files[base + "$id.js"] = src.toByteArray()
            """{"id":"$id","file":"$id.js","name":"Tool $id","version":"1.0","sha256":"${ScriptBytes.sha256(src)}","network":"$network"}"""
        }
        files[base + "index.json"] = """{"format":1,"tools":[${lines.joinToString(",")}]}""".toByteArray()
        requested.clear()
    }

    private fun until(pred: (CatalogState) -> Boolean): CatalogState = runBlocking { withTimeout(15_000) { controller.state.first(pred) } }
    private fun review(): CatalogState.Review = until { it is CatalogState.Review } as CatalogState.Review

    @Test fun `nothing is fetched until the action is opened`() {
        publish(script("tool-a"))
        assertEquals(CatalogState.Closed, controller.state.value)
        assertTrue(requested.isEmpty())
    }

    @Test fun `opening checks the catalog and shows the review`() {
        publish(script("tool-a"), script("tool-b"))
        controller.open()
        val s = review()
        assertEquals(listOf("tool-a", "tool-b"), s.plan.new.map { it.entry.id })
        assertTrue(s.canInstall); assertFalse(s.acknowledged)
        assertTrue(store.installed.value.isEmpty())
    }

    @Test fun `open does nothing while a check or a sheet is open`() {
        publish(script("tool-a"))
        controller.open(); review()
        val before = requested.toList()
        controller.open()
        assertEquals(before, requested)
    }

    @Test fun `Cancel while checking stops the fetch and closes`() {
        publish(script("tool-a"))
        gate = CompletableDeferred()
        controller.open()
        assertTrue(controller.state.value is CatalogState.Working)
        controller.close()
        assertEquals(CatalogState.Closed, controller.state.value)
        gate!!.complete(Unit)
        Thread.sleep(200)
        assertEquals(CatalogState.Closed, controller.state.value)
    }

    @Test fun `Cancel on the sheet saves nothing`() {
        publish(script("tool-a"))
        controller.open(); review()
        controller.close()
        assertEquals(CatalogState.Closed, controller.state.value)
        assertTrue(store.installed.value.isEmpty())
    }

    @Test fun `an index that cannot be fetched is an error that can be retried`() {
        publish(script("tool-a"))
        failIndex = FetchError.CONNECTION
        controller.open()
        assertEquals(CatalogState.Failed(ImportCopy.FETCH_CONNECTION), until { it is CatalogState.Failed })
        failIndex = null
        controller.retry()
        review()
    }

    @Test fun `Install saves everything and posts one notice`() {
        publish(script("tool-a"), script("tool-b"))
        controller.open(); review()
        controller.install()
        until { it == CatalogState.Closed }
        assertEquals("Installed 2 tools", controller.notice.value)
        assertEquals(setOf("user:tool-a", "user:tool-b"), store.installed.value.map { it.key }.toSet())
        controller.consumeNotice()
        assertNull(controller.notice.value)
    }

    @Test fun `a plan that widens access cannot be installed before the extra yes`() {
        publish(script("tool-a", "1.0", "target"))
        controller.open(); review(); controller.install(); until { it == CatalogState.Closed }
        publish(script("tool-a", "1.1", "any"))
        controller.consumeNotice()
        controller.open()
        val s = review()
        assertTrue(s.plan.needsYes); assertFalse(s.canInstall)
        controller.install()
        assertTrue("install ignored without the yes", controller.state.value is CatalogState.Review)
        assertEquals("1.0", store.moduleOf("user:tool-a")!!.descriptor.version)
        controller.setAcknowledged(true)
        assertTrue((controller.state.value as CatalogState.Review).canInstall)
        controller.install()
        until { it == CatalogState.Closed }
        assertEquals("1.1", store.moduleOf("user:tool-a")!!.descriptor.version)
    }

    @Test fun `an up to date catalog has nothing to install`() {
        publish(script("tool-a"))
        controller.open(); review(); controller.install(); until { it == CatalogState.Closed }
        controller.open()
        val s = review()
        assertEquals(1, s.plan.unchanged); assertFalse(s.canInstall)
        controller.install()
        assertTrue(controller.state.value is CatalogState.Review)
    }

    @Test fun `a partly failed install says how many went in`() {
        publish(script("tool-a"), script("tool-b"))
        controller.open(); val s = review()
        // the installed copy changes while the sheet is open, so one line is stale
        val other = script("tool-a", "1.0").replace("Tool tool-a", "Changed")
        runBlocking {
            val importer = ScriptImporter(engine, store, fetcher)
            val local = (importer.plan(other.toByteArray(), null, "x.js") as com.northwalessolutions.netprobe.engine.script.ImportOutcome.Ready).plan
            importer.confirm(local, true)
        }
        assertEquals(2, s.plan.new.size)
        controller.install()
        until { it == CatalogState.Closed }
        assertEquals("Installed 1 of 2 tools, 1 failed", controller.notice.value)
        assertNotNull(store.moduleOf("user:tool-b"))
    }

    @Test fun `an unexpected exception is logged and shown as a failure`() {
        publish(script("tool-a"))
        val boom = object : UpdateFetcher {
            override suspend fun fetch(url: URL, maxBytes: Long, out: OutputStream, onProgress: (Long, Long?) -> Unit): UpdateFetch = throw IllegalStateException("boom")
        }
        val c = CatalogController(scope, CatalogUpdater(ScriptImporter(engine, store, boom), store, boom, base + "index.json"), { logged.add(it) })
        c.open()
        assertEquals(CatalogState.Failed(ImportCopy.UNEXPECTED), runBlocking { withTimeout(15_000) { c.state.first { it is CatalogState.Failed } } })
        assertEquals(1, logged.size)
    }
}
