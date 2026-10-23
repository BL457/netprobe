package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.FindingKind
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.netenv.Ipv4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** ctx.web.snapshot and image findings against a fake renderer and the real scope guard (spec section 12). The WebView itself is not run here. */
class HostWebTest {
    @get:Rule val tmp = TemporaryFolder()
    private val renderer = FakePageRenderer()
    private val lanGuard = NetworkScopeGuard(NetworkScope.LAN, null, ScopeLan(Ipv4.parse("192.168.1.50")!!, 24)) { emptyList() }
    private val big = ScriptLimits(findingsPerSecond = 1_000_000, findingsBurst = 1_000_000)

    private fun store() = SnapshotStore(File(tmp.root, "snapshots"))
    private fun harness(store: SnapshotStore? = store(), limits: ScriptLimits = big, gate: ScopeGate = lanGuard) =
        Harness(limits = limits, gate = gate, renderer = renderer, snapshots = store, network = NetworkScope.LAN)

    private fun json(h: Harness, js: String): String {
        val r = h.body("function json(v) { return { status: 'done', summary: JSON.stringify(v) }; }\nfunction msg(f) { try { f(); return 'allowed'; } catch (e) { return e.message; } }\n$js")
        check(r.status == ToolRunStatus.DONE) { "run failed: ${r.summary}" }
        return r.summary
    }

    @Test fun `a snapshot returns the documented shape and stores a jpeg under the run folder`() {
        val st = store(); val h = harness(st)
        renderer.handler = { r, _ -> RenderedPage(401, "Router login", r.url + "login", renderer.jpeg) }
        assertEquals("""{"status":401,"httpStatus":401,"title":"Router login","finalUrl":"http://192.168.1.9/login","image":"img1","error":null}""",
            json(h, "return json(ctx.web.snapshot({ url: 'http://192.168.1.9/' }));"))
        val file = st.fileFor("7/1.jpg")
        assertNotNull(file); assertEquals(renderer.jpeg.toList(), file!!.readBytes().toList())
    }

    @Test fun `ids count up and each picture gets its own file`() {
        val st = store(); val h = harness(st)
        assertEquals("""["img1","img2","img3"]""", json(h, "var ids = []; for (var i = 0; i < 3; i++) { ids.push(ctx.web.snapshot({ url: 'http://192.168.1.9/' }).image); } return json(ids);"))
        assertTrue(listOf("7/1.jpg", "7/2.jpg", "7/3.jpg").all { st.fileFor(it) != null })
    }

    @Test fun `timeout and width default to 15 seconds and 1024 and are clamped`() {
        val h = harness()
        json(h, "ctx.web.snapshot({ url: 'http://192.168.1.9/' }); ctx.web.snapshot({ url: 'http://192.168.1.9/', timeoutMs: 1, width: 99999 }); ctx.web.snapshot({ url: 'http://192.168.1.9/', timeoutMs: 999999, width: 5 }); return json(1);")
        assertEquals(listOf(15_000, 1_000, 60_000), renderer.requests.map { it.timeoutMs })
        assertEquals(listOf(1_024, 2_048, 320), renderer.requests.map { it.width })
    }

    @Test fun `a bad url or option is an error the script can catch and the renderer is not called`() {
        val h = harness()
        for (js in listOf("ctx.web.snapshot({})", "ctx.web.snapshot('http://192.168.1.9/')", "ctx.web.snapshot({ url: 'file:///etc/passwd' })", "ctx.web.snapshot({ url: 'content://x/y' })",
            "ctx.web.snapshot({ url: 'ftp://192.168.1.9/' })", "ctx.web.snapshot({ url: 'http://192.168.1.9/', timeoutMs: 'soon' })")) {
            assertNotEquals(js, "\"allowed\"", json(h, "return json(msg(function () { $js; }));"))
        }
        assertTrue(renderer.requests.isEmpty())
    }

    private fun assertNotEquals(message: String, unexpected: String, actual: String) = assertFalse("$message: $actual", unexpected == actual)

    // The scope

    @Test fun `a first hop outside the scope is refused before the renderer is called`() {
        val h = harness()
        assertEquals("\"This tool can only contact addresses on the current network\"", json(h, "return json(msg(function () { ctx.web.snapshot({ url: 'http://8.8.8.8/' }); }));"))
        assertEquals("\"This address cannot be contacted by a tool\"", json(h, "return json(msg(function () { ctx.web.snapshot({ url: 'http://192.168.1.50:8080/' }); }));"))
        assertTrue(renderer.requests.isEmpty())
    }

    @Test fun `the renderer is handed the scope as its allow test for every hop`() {
        val h = harness()
        val seen = ArrayList<Pair<String, Boolean>>()
        renderer.handler = { r, _ ->
            for (u in listOf("http://192.168.1.9/", "http://192.168.1.10/next", "http://192.168.1.11/")) seen += u to r.allow(u, true)
            RenderedPage(200, "ok", "http://192.168.1.11/", renderer.jpeg)
        }
        json(h, "ctx.web.snapshot({ url: 'http://192.168.1.9/' }); return json(1);")
        assertTrue(seen.all { it.second })
    }

    @Test fun `a redirect hop outside the scope refuses the snapshot and nothing is stored`() {
        val st = store(); val h = harness(st)
        val answers = ArrayList<Boolean>()
        renderer.handler = { r, _ ->
            answers += r.allow("http://192.168.1.9/", true)
            answers += r.allow("http://8.8.8.8/landing", true)       // the redirect target
            RenderedPage(200, "elsewhere", "http://192.168.1.9/", renderer.jpeg)
        }
        assertEquals("\"This tool can only contact addresses on the current network\"", json(h, "return json(msg(function () { ctx.web.snapshot({ url: 'http://192.168.1.9/' }); }));"))
        assertEquals(listOf(true, false), answers)
        assertNull(st.fileFor("7/1.jpg"))
    }

    @Test fun `a page that ended on an address outside the scope is refused even if the renderer did not ask`() {
        val st = store(); val h = harness(st)
        renderer.handler = { _, _ -> RenderedPage(200, "elsewhere", "http://10.99.0.1:81/", renderer.jpeg) }
        assertEquals("\"This tool can only contact addresses on the current network\"", json(h, "return json(msg(function () { ctx.web.snapshot({ url: 'http://192.168.1.9/' }); }));"))
        assertNull(st.fileFor("7/1.jpg"))
    }

    @Test fun `a refused resource is left out and the page still succeeds`() {
        val h = harness()
        val answers = ArrayList<Boolean>()
        renderer.handler = { r, _ ->
            answers += r.allow("http://203.0.113.5/tracker.js", false)
            answers += r.allow("file:///data/data/x/secret", false)
            answers += r.allow("http://192.168.1.9/logo.png", false)
            RenderedPage(200, "ok", r.url, renderer.jpeg)
        }
        assertEquals("200", json(h, "return json(ctx.web.snapshot({ url: 'http://192.168.1.9/' }).status);"))
        assertEquals(listOf(false, false, true), answers)
    }

    @Test fun `the allow test refuses non web urls and malformed ones`() {
        val h = harness()
        val answers = ArrayList<Boolean>()
        renderer.handler = { r, _ ->
            for (u in listOf("file:///etc/hosts", "content://media/x", "javascript:alert(1)", "http://user:pw@192.168.1.9/", "http://192.168.1.9\\@evil/", "about:blank")) answers += r.allow(u, true)
            RenderedPage(200, "ok", r.url, renderer.jpeg)
        }
        // the page-level refusal is only recorded for a scope violation; malformed urls are simply not allowed
        json(h, "ctx.web.snapshot({ url: 'http://192.168.1.9/' }); return json(1);")
        assertTrue(answers.none { it })
    }

    @Test fun `a target tool may snapshot only its target and a tool with no scope nothing`() {
        val target = NetworkScopeGuard(NetworkScope.TARGET, ScopeTarget(Ipv4.parse("192.168.1.20")!!, null), ScopeLan(Ipv4.parse("192.168.1.50")!!, 24)) { emptyList() }
        val h = harness(gate = target)
        assertEquals("\"allowed\"", json(h, "return json(msg(function () { ctx.web.snapshot({ url: 'http://192.168.1.20/' }); }));"))
        assertEquals("\"This tool can only contact the device it was run against\"", json(h, "return json(msg(function () { ctx.web.snapshot({ url: 'http://192.168.1.21/' }); }));"))
        val none = harness(gate = DenyAllGate)
        assertEquals("\"This tool has no network access\"", json(none, "return json(msg(function () { ctx.web.snapshot({ url: 'http://192.168.1.20/' }); }));"))
    }

    // Caps and budgets

    @Test fun `at most 200 pictures per run`() {
        val h = harness()
        assertEquals("""{"ok":200,"last":"At most 200 page snapshots can be taken in one run"}""",
            json(h, "var ok = 0, last = ''; for (var i = 0; i < 201; i++) { try { ctx.web.snapshot({ url: 'http://192.168.1.9/' }); ok++; } catch (e) { last = e.message; } } return json({ ok: ok, last: last });"))
        assertEquals(200, renderer.requests.size)
    }

    @Test fun `at most 20 MB of pictures per run`() {
        val h = harness()
        val six = ByteArray(6 * 1024 * 1024)
        renderer.handler = { r, _ -> RenderedPage(200, "big", r.url, six) }
        assertEquals("""{"ok":3,"last":"Page snapshots can use at most 20 MB in one run"}""",
            json(h, "var ok = 0, last = ''; for (var i = 0; i < 5; i++) { try { ctx.web.snapshot({ url: 'http://192.168.1.9/' }); ok++; } catch (e) { last = e.message; } } return json({ ok: ok, last: last });"))
    }

    @Test fun `a snapshot counts as a host call`() {
        val h = harness(limits = ScriptLimits(maxHostCalls = 2, findingsPerSecond = 1_000_000, findingsBurst = 1_000_000))
        val r = h.body("ctx.web.snapshot({ url: 'http://192.168.1.9/' }); ctx.web.snapshot({ url: 'http://192.168.1.9/' }); ctx.web.snapshot({ url: 'http://192.168.1.9/' }); return { status: 'done', summary: 'reached' };")
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("Script made more than 20,000 network calls", r.summary)
        assertEquals(2, renderer.requests.size)
    }

    @Test fun `the picture counts against the transfer budget`() {
        val h = harness(limits = ScriptLimits(maxTransferBytes = 10, findingsPerSecond = 1_000_000, findingsBurst = 1_000_000))
        val r = h.body("ctx.web.snapshot({ url: 'http://192.168.1.9/' }); ctx.web.snapshot({ url: 'http://192.168.1.9/' }); return { status: 'done', summary: 'reached' };")
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("Script transferred more than 50 MB", r.summary)
    }

    @Test fun `the gate is taken before the renderer`() {
        val h = harness()
        json(h, "ctx.web.snapshot({ url: 'http://192.168.1.9/' }); return json(1);")
        assertEquals(1, h.control.gates.get())
    }

    // Failure and Stop

    @Test fun `a page that failed has no image, no file, no charge and does not use up the caps`() {
        val st = store(); val h = harness(st, limits = ScriptLimits(maxTransferBytes = 10, findingsPerSecond = 1_000_000, findingsBurst = 1_000_000))
        renderer.handler = { r, _ -> RenderedPage(503, "Unavailable", r.url, null, "The page answered with HTTP 503") }
        assertEquals("""{"status":503,"httpStatus":503,"title":"Unavailable","finalUrl":"http://192.168.1.9/","image":null,"error":"The page answered with HTTP 503"}""",
            json(h, "var r = ctx.web.snapshot({ url: 'http://192.168.1.9/' }); for (var i = 0; i < 300; i++) { ctx.web.snapshot({ url: 'http://192.168.1.9/' }); } return json(r);"))
        assertNull(st.fileFor("7/1.jpg"))
        renderer.handler = { r, _ -> RenderedPage(200, "ok", r.url, renderer.jpeg) }
        assertEquals("\"img1\"", json(h, "return json(ctx.web.snapshot({ url: 'http://192.168.1.9/' }).image);"))
        assertNotNull(st.fileFor("7/1.jpg"))
    }

    @Test fun `a successful page reports its status twice and a null error`() {
        val h = harness()
        assertEquals("""{"status":200,"httpStatus":200,"image":"img1","error":null}""",
            json(h, "var r = ctx.web.snapshot({ url: 'http://192.168.1.9/' }); return json({ status: r.status, httpStatus: r.httpStatus, image: r.image, error: r.error });"))
    }

    @Test fun `a failed page that ended off the network is still refused`() {
        val h = harness()
        renderer.handler = { _, _ -> RenderedPage(500, "x", "http://8.8.8.8/", null, "e") }
        assertEquals("\"This tool can only contact addresses on the current network\"", json(h, "return json(msg(function () { ctx.web.snapshot({ url: 'http://192.168.1.9/' }); }));"))
    }

    @Test fun `the settle time defaults to 2500 ms and is clamped to 0 to 10000`() {
        val h = harness()
        json(h, "ctx.web.snapshot({ url: 'http://192.168.1.9/' }); ctx.web.snapshot({ url: 'http://192.168.1.9/', settleMs: 0 }); ctx.web.snapshot({ url: 'http://192.168.1.9/', settleMs: -5 }); ctx.web.snapshot({ url: 'http://192.168.1.9/', settleMs: 99999 }); return json(1);")
        assertEquals(listOf(2_500, 0, 0, 10_000), renderer.requests.map { it.settleMs })
    }

    @Test fun `settle time is cut to fit the timeout and never goes negative`() {
        assertEquals(2_500L, SettleTime.clip(2_500, 10_000))
        assertEquals(1_500L, SettleTime.clip(2_500, 2_000))
        assertEquals(0L, SettleTime.clip(2_500, 500))
        assertEquals(0L, SettleTime.clip(2_500, -3_000))
        assertEquals(0L, SettleTime.clip(0, 10_000))
    }

    @Test fun `a flat picture is blank, a picture with any real difference is not`() {
        val white = 0xFFFFFFFF.toInt()
        assertTrue(BlankCheck.isFlat(IntArray(256) { white }))
        assertTrue(BlankCheck.isFlat(IntArray(256) { 0xFF000000.toInt() }))
        assertTrue("noise of one level is still flat", BlankCheck.isFlat(IntArray(256) { if (it % 2 == 0) white else 0xFFFEFEFE.toInt() }))
        assertTrue(BlankCheck.isFlat(IntArray(0)))
        assertFalse("a logo on white", BlankCheck.isFlat(IntArray(256) { if (it == 40) 0xFF336699.toInt() else white }))
        assertFalse("white and black", BlankCheck.isFlat(IntArray(256) { if (it < 128) white else 0xFF000000.toInt() }))
        assertFalse("a dark grey page is not white", BlankCheck.isFlat(IntArray(256) { if (it == 0) white else 0xFF202020.toInt() }))
    }

    @Test fun `a page that cannot be drawn is an error the script can catch`() {
        val h = harness()
        renderer.handler = { _, _ -> throw RenderFailure("The page took too long to load") }
        assertEquals("\"The page took too long to load\"", json(h, "return json(msg(function () { ctx.web.snapshot({ url: 'http://192.168.1.9/' }); }));"))
    }

    @Test fun `no renderer or no store makes snapshots unavailable`() {
        val noStore = harness(store = null)
        assertEquals("\"Page snapshots are not available\"", json(noStore, "return json(msg(function () { ctx.web.snapshot({ url: 'http://192.168.1.9/' }); }));"))
        val noRenderer = Harness(gate = lanGuard, snapshots = store(), network = NetworkScope.LAN)
        assertEquals("\"Page snapshots are not available\"", json(noRenderer, "return json(msg(function () { ctx.web.snapshot({ url: 'http://192.168.1.9/' }); }));"))
    }

    @Test fun `a picture that cannot be written is an error and nothing is issued`() {
        val blocked = File(tmp.root, "snapshots").also { it.writeText("a file where the folder should be") }
        val h = harness(SnapshotStore(blocked))
        assertEquals("\"The snapshot could not be saved\"", json(h, "return json(msg(function () { ctx.web.snapshot({ url: 'http://192.168.1.9/' }); }));"))
    }

    @Test fun `stopping the run ends a snapshot that is still drawing`() {
        val h = harness()
        val entered = CountDownLatch(1)
        renderer.handler = { _, io -> entered.countDown(); while (true) { io.ensureAlive(); Thread.sleep(10) }; @Suppress("UNREACHABLE_CODE") error("unreachable") }
        val job = CoroutineScope(Dispatchers.Default).launch {
            h.engine.run(ScriptRunRequest("tool = { run: function (i, ctx) { ctx.web.snapshot({ url: 'http://192.168.1.9/' }); return {}; } };", emptyMap(), h.host()))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        runBlocking { withTimeout(3_000) { job.cancelAndJoin() } }
        assertTrue(job.isCancelled)
    }

    // Image findings

    @Test fun `a finding with an image id stores the picture path and keeps its other fields`() {
        val h = harness()
        json(h, "var s = ctx.web.snapshot({ url: 'http://192.168.1.9/' }); ctx.finding('page', 'NAS', s.title, 'http://192.168.1.9/', { image: s.image }); return json(1);")
        val f = h.progress.findings.single()
        assertEquals(FindingKind.OTHER, f.kind); assertEquals("NAS", f.value); assertEquals("Fake", f.detail); assertEquals("http://192.168.1.9/", f.url)
        assertEquals(mapOf("kind" to "page", "image" to "7/1.jpg"), f.extra)
    }

    @Test fun `a built in kind with an image keeps the kind and only adds the image`() {
        val h = harness()
        json(h, "var s = ctx.web.snapshot({ url: 'http://192.168.1.9/' }); ctx.finding('TITLE', 'NAS', null, null, { image: s.image }); return json(1);")
        val f = h.progress.findings.single()
        assertEquals(FindingKind.TITLE, f.kind); assertEquals(mapOf("image" to "7/1.jpg"), f.extra)
    }

    @Test fun `a finding without extra is unchanged`() {
        val h = harness()
        json(h, "ctx.finding('TITLE', 'x'); ctx.finding('TITLE', 'y', null, null, {}); ctx.finding('TITLE', 'z', null, null, { other: 1 }); return json(1);")
        assertTrue(h.progress.findings.all { it.extra.isEmpty() })
    }

    @Test fun `an image id that was not issued to this run is refused`() {
        val h = harness()
        val refused = "\"The image was not made by ctx.web.snapshot in this run\""
        json(h, "ctx.web.snapshot({ url: 'http://192.168.1.9/' }); return json(1);")
        for (id in listOf("img2", "img0", "../../x", "7/1.jpg", "../7/1.jpg", "/data/data/app/files/netprobe.db", "", "IMG1")) {
            val escaped = id.replace("\\", "\\\\").replace("'", "\\'")
            assertEquals(id, refused, json(h, "return json(msg(function () { ctx.finding('TITLE', 'x', null, null, { image: '$escaped' }); }));"))
        }
        assertTrue(h.progress.findings.isEmpty())
    }

    @Test fun `an image id from another run is refused`() {
        val first = harness()
        json(first, "ctx.web.snapshot({ url: 'http://192.168.1.9/' }); return json(1);")
        val second = harness()
        assertEquals("\"The image was not made by ctx.web.snapshot in this run\"", json(second, "return json(msg(function () { ctx.finding('TITLE', 'x', null, null, { image: 'img1' }); }));"))
    }

    @Test fun `an image that is not text, and extra that is not an object, are catchable errors`() {
        val h = harness()
        json(h, "ctx.web.snapshot({ url: 'http://192.168.1.9/' }); return json(1);")
        assertTrue(json(h, "return json(msg(function () { ctx.finding('TITLE', 'x', null, null, { image: 1 }); }));") != "\"allowed\"")
        assertTrue(json(h, "return json(msg(function () { ctx.finding('TITLE', 'x', null, null, 'img1'); }));") != "\"allowed\"")
        assertTrue(h.progress.findings.isEmpty())
    }

    // The store

    @Test fun `fileFor resolves only the exact shape inside the root`() {
        val st = store()
        val path = st.write(3, 1, byteArrayOf(1))
        assertEquals("3/1.jpg", path)
        assertNotNull(st.fileFor("3/1.jpg"))
        File(tmp.root, "secret.jpg").writeBytes(byteArrayOf(9))
        for (bad in listOf("../secret.jpg", "3/../../secret.jpg", "/3/1.jpg", "3/1.png", "3/1.jpg/", "3/01x.jpg", "3//1.jpg", "3\\1.jpg", "", "3/2.jpg", "x/1.jpg", "3/1.jpg\u0000")) {
            assertNull(bad, st.fileFor(bad))
        }
    }

    @Test fun `deleting a run removes its folder and no other`() {
        val st = store()
        st.write(3, 1, byteArrayOf(1)); st.write(3, 2, byteArrayOf(1)); st.write(4, 1, byteArrayOf(1))
        st.deleteRun(3)
        assertNull(st.fileFor("3/1.jpg")); assertNull(st.fileFor("3/2.jpg")); assertNotNull(st.fileFor("4/1.jpg"))
        st.deleteRun(99)
    }

    @Test fun `a sweep deletes folders of runs that are gone and keeps the live ones, newer ones and strangers`() {
        val st = store()
        for (id in listOf(1L, 2L, 3L, 9L)) st.write(id, 1, byteArrayOf(1))
        File(st.root, "notes").mkdirs(); File(st.root, "notes/a.txt").writeText("keep")
        assertEquals(1, st.sweep(liveRunIds = setOf(2L, 3L), highestRunId = 5L))      // run 1 is gone; run 9 started after the ids were read
        assertNull(st.fileFor("1/1.jpg")); assertNotNull(st.fileFor("2/1.jpg")); assertNotNull(st.fileFor("3/1.jpg")); assertNotNull(st.fileFor("9/1.jpg"))
        assertTrue(File(st.root, "notes/a.txt").exists())
        assertEquals(1, st.sweep(liveRunIds = setOf(2L, 3L)))                         // without the bound, run 9 is gone too
        assertNull(st.fileFor("9/1.jpg"))
        assertEquals(2, st.sweep(emptySet()))
        assertNull(st.fileFor("2/1.jpg"))
    }

    @Test fun `sweeping a store that was never written is harmless`() {
        assertEquals(0, store().sweep(setOf(1L)))
    }
}
