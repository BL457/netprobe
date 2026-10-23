package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.script.ImportPlan
import com.northwalessolutions.netprobe.engine.script.RhinoScriptEngine
import com.northwalessolutions.netprobe.engine.script.ScriptAssets
import com.northwalessolutions.netprobe.engine.script.ScriptImporter
import com.northwalessolutions.netprobe.engine.script.ScriptToolModule
import com.northwalessolutions.netprobe.engine.script.ScriptToolStore
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.net.URL

/** The import flow's state machine: choose, work, review or fail, acknowledge, save. */
class ToolImportControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val env = NetworkEnv(Ipv4.parse("192.168.1.50")!!, 24, null, emptyList(), null, null, Transport.WIFI)
    private val engine = RhinoScriptEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logged = ArrayList<Throwable>()
    private val store by lazy { ScriptToolStore(ScriptAssets { null }, engine, { d, s -> ScriptToolModule(d, s, engine, { env }, { null }) }, {}, File(tmp.root, "tools")) }
    private var fetcher: UpdateFetcher = object : UpdateFetcher {
        override suspend fun fetch(url: URL, maxBytes: Long, out: OutputStream, onProgress: (Long, Long?) -> Unit): UpdateFetch = UpdateFetch.Ok(0)
    }
    private val controller by lazy { ToolImportController(scope, ScriptImporter(engine, store, delegating()), { logged.add(it) }) }

    /** The controller is built once, but each test sets [fetcher] before it starts a fetch. */
    private fun delegating() = object : UpdateFetcher {
        override suspend fun fetch(url: URL, maxBytes: Long, out: OutputStream, onProgress: (Long, Long?) -> Unit): UpdateFetch = fetcher.fetch(url, maxBytes, out, onProgress)
    }

    @After fun stop() { scope.coroutineContext[Job]?.cancel() }

    private fun script(version: String = "1.0", network: String = "target") =
        """tool = { id: "my-tool", name: "My tool", version: "$version", target: "none", network: "$network", run: function () {} };"""

    private fun until(pred: (ImportState) -> Boolean): ImportState = runBlocking { withTimeout(15_000) { controller.state.first(pred) } }
    private fun review(): ImportState.Review = until { it is ImportState.Review } as ImportState.Review
    private fun file(source: String) { controller.importFile("t.js") { ByteArrayInputStream(source.toByteArray()) } }

    @Test fun `it starts closed and opens on the first dialog`() {
        assertEquals(ImportState.Closed, controller.state.value)
        controller.open()
        assertEquals(ImportState.Choosing, controller.state.value)
        controller.close()
        assertEquals(ImportState.Closed, controller.state.value)
    }

    @Test fun `a file goes to review and Import saves it and posts one notice`() {
        controller.open(); file(script())
        val r = review()
        assertFalse(r.acknowledged)
        assertTrue(store.installed.value.isEmpty())
        controller.confirm()
        until { it is ImportState.Closed }
        assertEquals("Imported My tool", runBlocking { withTimeout(5_000) { controller.notice.first { it != null } } })
        assertEquals(listOf("user:my-tool"), store.installed.value.map { it.key })
        controller.consumeNotice()
        assertNull(controller.notice.value)
    }

    @Test fun `Cancel on the review drops the plan and saves nothing`() {
        file(script()); review()
        controller.close()
        assertEquals(ImportState.Closed, controller.state.value)
        assertTrue(store.installed.value.isEmpty())
    }

    @Test fun `problems show as a list and Import again goes back to the first dialog`() {
        file("tool = {")
        val f = until { it is ImportState.Failed } as ImportState.Failed
        assertTrue(f.errors.isNotEmpty())
        controller.again()
        assertEquals(ImportState.Choosing, controller.state.value)
        controller.again()
        assertEquals(ImportState.Choosing, controller.state.value)
    }

    @Test fun `a widening replace waits for the extra yes`() {
        file(script("1.0", "target")); review(); controller.confirm(); until { it is ImportState.Closed }
        file(script("1.1", "any"))
        val r = review()
        assertTrue(r.plan.needsWideningAck)
        controller.confirm()
        assertEquals(r, controller.state.value)
        assertEquals("1.0", store.installed.value.single().version)
        controller.setAcknowledged(true)
        assertTrue((controller.state.value as ImportState.Review).acknowledged)
        controller.confirm()
        until { it is ImportState.Closed }
        assertEquals("1.1", store.installed.value.single().version)
        assertEquals("Replaced My tool", controller.notice.value)
    }

    @Test fun `Cancel stops a fetch in flight`() {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        fetcher = object : UpdateFetcher {
            override suspend fun fetch(url: URL, maxBytes: Long, out: OutputStream, onProgress: (Long, Long?) -> Unit): UpdateFetch { started.complete(Unit); gate.await(); return UpdateFetch.Ok(0) }
        }
        controller.importUrl("https://example.org/t.js")
        assertEquals(ImportState.Working(fromUrl = true), controller.state.value)
        runBlocking { withTimeout(5_000) { started.await() } }
        controller.close()
        assertEquals(ImportState.Closed, controller.state.value)
        Thread.sleep(100)
        assertEquals(ImportState.Closed, controller.state.value)
    }

    @Test fun `a second start while working is ignored`() {
        val gate = CompletableDeferred<Unit>()
        fetcher = object : UpdateFetcher {
            override suspend fun fetch(url: URL, maxBytes: Long, out: OutputStream, onProgress: (Long, Long?) -> Unit): UpdateFetch { gate.await(); return UpdateFetch.Ok(0) }
        }
        controller.importUrl("https://example.org/t.js")
        file(script())
        assertEquals(ImportState.Working(fromUrl = true), controller.state.value)
        controller.close()
    }

    @Test fun `an unexpected exception becomes one error and is logged`() {
        controller.importFile("t.js") { throw IllegalStateException("boom") }
        val f = until { it is ImportState.Failed } as ImportState.Failed
        assertEquals(listOf(ImportCopy.UNEXPECTED), f.errors)
        assertEquals(1, logged.size)
    }

    @Test fun `the review holds the plan the sheet draws`() {
        file(script())
        val plan: ImportPlan = review().plan
        assertEquals("user:my-tool", plan.descriptor.key)
    }
}
