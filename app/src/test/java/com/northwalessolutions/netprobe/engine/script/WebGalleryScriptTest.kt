package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.FindingKind
import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import com.northwalessolutions.netprobe.engine.tools.ToolIcon
import com.northwalessolutions.netprobe.engine.tools.ToolInputs
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The catalog's web-gallery.js run for real through ScriptToolModule, the real guard, a fake transport and a fake renderer (spec section 12). */
class WebGalleryScriptTest {
    @get:Rule val tmp = TemporaryFolder()
    private val source = File("../catalog/web-gallery.js").readText(Charsets.UTF_8).replace("\r\n", "\n")
    private val engine = RhinoScriptEngine(ScriptLimits(findingsPerSecond = 1_000_000, findingsBurst = 1_000_000))
    private val transport = FakeScriptTransport()
    private val renderer = FakePageRenderer()
    private val progress = RecordingProgress()
    private val env = NetworkEnv(Ipv4.parse("192.168.1.50")!!, 24, null, emptyList(), null, null, Transport.WIFI)

    private fun port(n: Int, service: String = "HTTP", tls: Boolean = false) = ScriptPort(n, service, tls)
    private fun device(n: Int, vararg ports: ScriptPort, name: String = "192.168.1.$n") = ScriptDevice("192.168.1.$n", "", name, "", ports.toList())

    private fun module(devices: List<ScriptDevice>, snapshots: SnapshotStore? = SnapshotStore(File(tmp.root, "snapshots")), pageRenderer: PageRenderer = renderer): ScriptToolModule {
        val loaded = runBlocking { engine.evaluate(source) } as ScriptEvaluation.Loaded
        val d = (ScriptDescriptorParser.parse(loaded.tool, loaded.hasRun, ToolKeys.USER_PREFIX, null, ToolSource.BuiltIn) as ParsedTool.Valid).descriptor
        return ScriptToolModule(d, source, engine, { env }, { null }, transportFor = { _, _ -> transport }, devices = { devices }, renderer = pageRenderer, snapshots = snapshots)
    }
    private fun run(m: ScriptToolModule, inputs: Map<String, Any?> = emptyMap(), control: com.northwalessolutions.netprobe.engine.tools.RunControl = FakeControl()) =
        runBlocking { m.run(null, ToolInputs(inputs), control, progress) }
    private fun urls() = renderer.requests.map { it.url }

    @Test fun `it takes a picture of each open web port and says how many pages from how many devices`() {
        val devices = listOf(device(20, port(80), port(22, "SSH")), device(30, port(8080), port(32400)), device(40))
        val r = run(module(devices))
        assertEquals(r.summary, ToolRunStatus.DONE, r.status)
        assertEquals("3 pages from 2 devices", r.summary)
        assertEquals(listOf("http://192.168.1.20/", "http://192.168.1.30:8080/", "http://192.168.1.30:32400/"), urls())
        assertEquals(3, progress.findings.size)
    }

    @Test fun `each finding is a page finding with the picture, the title as detail and the page as its link`() {
        renderer.handler = { q, _ -> RenderedPage(200, "Synology DiskStation", q.url, renderer.jpeg) }
        run(module(listOf(device(20, port(5000), name = "nas"))))
        val f = progress.findings.single()
        assertEquals(FindingKind.OTHER, f.kind)
        assertEquals("nas  192.168.1.20:5000", f.value); assertEquals("Synology DiskStation", f.detail); assertEquals("http://192.168.1.20:5000/", f.url)
        assertEquals("page", f.extra["kind"]); assertEquals("0/1.jpg", f.extra["image"])
        assertNotNull(SnapshotStore(File(tmp.root, "snapshots")).fileFor("0/1.jpg"))
    }

    @Test fun `a device with no name of its own is listed by address and a page with no title says so`() {
        renderer.handler = { q, _ -> RenderedPage(200, "", q.url, renderer.jpeg) }
        run(module(listOf(device(20, port(80)))))
        val f = progress.findings.single()
        assertEquals("192.168.1.20", f.value); assertEquals("No title", f.detail)
    }

    private fun failedPage(q: RenderRequest, status: Int, error: String) = RenderedPage(status, "Error", q.url, null, error)

    @Test fun `an error page gives no picture and no finding, and is counted as not answering`() {
        renderer.handler = { q, _ -> failedPage(q, 500, "The page answered with HTTP 500") }
        val r = run(module(listOf(device(20, port(8008)), device(30, port(8443)))))
        assertEquals(ToolRunStatus.DONE, r.status)
        assertEquals("0 pages from 0 devices, 2 did not answer", r.summary)
        assertTrue(progress.findings.isEmpty())
        assertNull(SnapshotStore(File(tmp.root, "snapshots")).fileFor("0/1.jpg"))
    }

    @Test fun `a login page gets one plain line without a picture and is not counted as not answering`() {
        renderer.handler = { q, _ -> if (q.url.contains(".20")) failedPage(q, 401, "The page answered with HTTP 401") else failedPage(q, 403, "x") }
        val r = run(module(listOf(device(20, port(80), name = "router"), device(30, port(80)))))
        assertEquals("0 pages from 0 devices", r.summary)
        assertEquals(listOf("router  192.168.1.20  login required", "192.168.1.30  login required"), progress.findings.map { it.value })
        assertEquals(listOf("HTTP 401", "HTTP 403"), progress.findings.map { it.detail })
        assertTrue(progress.findings.all { it.extra["image"] == null && it.url != null })
    }

    @Test fun `a page that never answered or drew nothing is skipped silently`() {
        renderer.handler = { q, _ -> if (q.url.contains(".20")) RenderedPage(0, "", q.url, null, "The page could not be loaded: net::ERR_CONNECTION_REFUSED") else RenderedPage(200, "", q.url, null, "The page drew nothing") }
        val r = run(module(listOf(device(20, port(80)), device(30, port(80)), device(40, port(80)))))
        assertEquals("0 pages from 0 devices, 3 did not answer", r.summary)
        assertTrue(progress.findings.isEmpty())
    }

    @Test fun `good pages and failed pages mix`() {
        renderer.handler = { q, _ -> if (q.url.contains(".30")) failedPage(q, 404, "e") else RenderedPage(200, "ok", q.url, renderer.jpeg) }
        val r = run(module(listOf(device(20, port(80)), device(30, port(80)), device(40, port(80)))))
        assertEquals("2 pages from 2 devices, 1 did not answer", r.summary)
        assertEquals(2, progress.findings.size)
    }

    @Test fun `seconds to let a page draw become the settle time`() {
        run(module(listOf(device(20, port(80)))))
        run(module(listOf(device(20, port(80)))), mapOf("settleS" to 0))
        run(module(listOf(device(20, port(80)))), mapOf("settleS" to 7))
        assertEquals(listOf(3_000, 0, 7_000), renderer.requests.map { it.settleMs })
    }

    @Test fun `https is chosen for 443, 8443 and 5001 and for a port the scan saw TLS on`() {
        val devices = listOf(device(20, port(443), port(5001), port(8443), port(9000, "HTTPS", tls = true), port(8080), port(8000, "HTTP", tls = false)))
        run(module(devices), mapOf("ports" to "80,443,8080,8443,8000,9000,5001"))
        assertEquals(setOf("https://192.168.1.20/", "https://192.168.1.20:5001/", "https://192.168.1.20:8443/", "https://192.168.1.20:9000/", "http://192.168.1.20:8080/", "http://192.168.1.20:8000/"),
            urls().toSet())
        assertEquals(6, urls().size)
    }

    @Test fun `only open ports are tried by default and the ports list narrows them`() {
        val devices = listOf(device(20, port(80), port(443), port(2222, "SSH")), device(30, port(9100, "RAW")))
        run(module(devices))
        assertEquals(listOf("http://192.168.1.20/", "https://192.168.1.20/"), urls())
        renderer.requests.clear()
        run(module(devices), mapOf("ports" to "443, 99999, x, 443"))
        assertEquals(listOf("https://192.168.1.20/"), urls())
    }

    @Test fun `with only open off every port in the list is tried on every device`() {
        val devices = listOf(device(20, port(443, "HTTPS", tls = true)), device(30))
        val r = run(module(devices), mapOf("onlyOpen" to false, "ports" to "80,443", "maxPages" to 200))
        assertEquals(r.summary, ToolRunStatus.DONE, r.status)
        assertEquals(listOf("http://192.168.1.20/", "https://192.168.1.20/", "http://192.168.1.30/", "https://192.168.1.30/"), urls())
    }

    @Test fun `the page cap stops the run and counts the pages tried`() {
        val devices = (1..10).map { device(it + 10, port(80), port(8080)) }
        val r = run(module(devices), mapOf("maxPages" to 3))
        assertEquals("3 pages from 2 devices", r.summary)
        assertEquals(3, renderer.requests.size)
        renderer.requests.clear()
        run(module(devices), mapOf("maxPages" to 1))
        assertEquals(1, renderer.requests.size)
    }

    @Test fun `the default cap is 60 pages`() {
        val devices = (1..100).map { device(it + 100, port(80)) }   // 101 to 200: clear of the phone itself (.50)
        val r = run(module(devices))
        assertEquals("60 pages from 60 devices", r.summary)
        assertEquals(60, renderer.requests.size)
    }

    @Test fun `seconds per page become the render timeout`() {
        run(module(listOf(device(20, port(80)))))
        run(module(listOf(device(20, port(80)))), mapOf("timeoutS" to 25))
        assertEquals(listOf(10_000, 25_000), renderer.requests.map { it.timeoutMs })
    }

    @Test fun `a page that fails is skipped and the run goes on`() {
        renderer.handler = { q, _ -> if (q.url.contains(":8080")) throw RenderFailure("The page took too long to load") else RenderedPage(200, "ok", q.url, renderer.jpeg) }
        val r = run(module(listOf(device(20, port(80), port(8080)), device(30, port(8080)), device(40, port(443)))))
        assertEquals(ToolRunStatus.DONE, r.status)
        assertEquals("2 pages from 2 devices, 2 did not answer", r.summary)
        assertEquals(4, renderer.requests.size)
        assertEquals(listOf("192.168.1.20", "192.168.1.40"), progress.findings.map { it.value })
    }

    @Test fun `no devices is an empty gallery, not a failure`() {
        val r = run(module(emptyList()))
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("0 pages from 0 devices", r.summary)
        assertTrue(renderer.requests.isEmpty())
    }

    @Test fun `a phone with no way to draw pages fails clearly`() {
        val r = run(module(listOf(device(20, port(80))), snapshots = null))
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("Failed: Page snapshots are not available", r.summary)
        val noRenderer = run(module(listOf(device(20, port(80))), pageRenderer = NoPageRenderer))
        assertEquals(ToolRunStatus.FAILED, noRenderer.status); assertEquals("Failed: Page snapshots are not available", noRenderer.summary)
    }

    @Test fun `the run's picture limit ends the gallery politely with the pictures it has`() {
        val six = ByteArray(6 * 1024 * 1024)
        renderer.handler = { q, _ -> RenderedPage(200, "big", q.url, six) }
        val r = run(module((1..8).map { device(it + 10, port(80)) }))
        assertEquals(ToolRunStatus.DONE, r.status)
        assertEquals("3 pages from 3 devices, stopped: Page snapshots can use at most 20 MB in one run", r.summary)
    }

    @Test fun `stopping the run mid way ends it without finishing the list`() {
        val reached = CountDownLatch(2)
        renderer.handler = { q, io ->
            reached.countDown()
            if (reached.count == 0L) while (true) { io.ensureAlive(); Thread.sleep(10) }
            RenderedPage(200, "ok", q.url, renderer.jpeg)
        }
        val m = module((1..10).map { device(it + 10, port(80)) })
        val job = CoroutineScope(Dispatchers.Default).launch { m.run(null, ToolInputs(emptyMap()), FakeControl(), progress) }
        assertTrue(reached.await(10, TimeUnit.SECONDS))
        runBlocking { withTimeout(5_000) { job.cancelAndJoin() } }
        assertTrue(job.isCancelled)
        assertEquals(2, renderer.requests.size)
        assertEquals(1, progress.findings.size)
    }

    @Test fun `every page the gallery opens passes the lan guard, so an address off the network is never offered to the renderer`() {
        val r = run(module(listOf(ScriptDevice("8.8.8.8", "", "dns", "", listOf(port(80))), device(20, port(80)))))
        assertEquals("1 page from 1 device, 1 did not answer", r.summary)
        assertEquals(listOf("http://192.168.1.20/"), urls())
    }

    @Test fun `the descriptor is a lan tool with no target, so ctx devices is allowed`() {
        val d = module(emptyList()).descriptor
        assertEquals("user:web-gallery", d.key); assertEquals(TargetKind.NONE, d.target); assertEquals(NetworkScope.LAN, d.network)
        assertEquals(ToolCategory.WEB, d.category); assertEquals(ToolIcon.WEB, d.icon); assertEquals(false, d.broadcast)
    }

    @Test fun `the inputs and their defaults are as the catalog promises`() {
        val inputs = module(emptyList()).descriptor.inputs
        assertEquals(listOf("ports", "onlyOpen", "timeoutS", "settleS", "maxPages"), inputs.map { it.name })
        val ports = inputs[0] as InputSpec.TextList
        assertEquals("80,443,8080,8443,8000,8008,8081,8096,8123,9000,5000,5001,32400", ports.default); assertEquals(false, ports.required)
        assertEquals(true, (inputs[1] as InputSpec.Bool).default)
        val timeout = inputs[2] as InputSpec.Number
        assertEquals(Triple(10, 3, 30), Triple(timeout.default, timeout.min, timeout.max))
        val settle = inputs[3] as InputSpec.Number
        assertEquals(Triple(3, 0, 10), Triple(settle.default, settle.min, settle.max))
        val pages = inputs[4] as InputSpec.Number
        assertEquals(Triple(60, 1, 200), Triple(pages.default, pages.min, pages.max))
    }

    @Test fun `out of range inputs are refused by the form rules`() {
        for (bad in listOf(mapOf("timeoutS" to 2), mapOf("timeoutS" to 31), mapOf("settleS" to -1), mapOf("settleS" to 11), mapOf("maxPages" to 0), mapOf("maxPages" to 201))) {
            val r = run(module(listOf(device(20, port(80)))), bad)
            assertEquals(bad.toString(), ToolRunStatus.FAILED, r.status)
        }
        assertTrue(renderer.requests.isEmpty())
    }
}
