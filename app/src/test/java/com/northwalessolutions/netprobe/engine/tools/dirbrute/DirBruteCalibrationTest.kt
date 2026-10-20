package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.HttpFailure
import com.northwalessolutions.netprobe.engine.common.HttpResponse
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.TestControl
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.options
import com.northwalessolutions.netprobe.engine.tools.RunControl
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.resp
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.sized
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.target
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.tool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Review Focus 2: calibration and the not-found filter (spec 5.4). */
class DirBruteCalibrationTest {
    private suspend fun run(ex: Exchange, words: List<String>, o: DirBruteOptions = DirBruteOptions(concurrency = 1)): Pair<com.northwalessolutions.netprobe.engine.tools.ToolResult, CollectingProgress> {
        val p = CollectingProgress(); return tool(ex, words).run(target(), options(o), TestControl(), p) to p
    }

    /** A catch-all whose random-path sizes vary as [sampleSize] (by GET count); word paths answer [wordSizes], /admin is real. */
    private fun nonceServer(sampleSize: (Int) -> Int, wordSizes: Map<String, Int>): Exchange {
        var n = 0
        return Exchange { method, path -> when {
            path == "/admin" -> sized(200, 5000)
            wordSizes.containsKey(path) -> sized(200, wordSizes.getValue(path))
            method == "GET" && path == "/abcdef012345" -> sized(200, sampleSize(n++))
            else -> sized(200, sampleSize(0))
        } }
    }
    @Test fun `a nonce varying catch all is filtered by the band, including sizes inside the pad but outside the samples`() = runTest {
        // Samples 100 to 104: spread 4, so pad = PAD_MIN 32 and the band is 68 to 136. 70 and 130 lie outside every sample.
        val ex = nonceServer({ 100 + it % 5 }, mapOf("/home" to 70, "/about" to 130, "/contact" to 102))
        val (r, p) = run(ex, listOf("home", "about", "admin", "contact"))
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals(listOf("/admin"), p.paths())
    }
    @Test fun `a widely spread catch all pads by its spread`() = runTest {
        // Samples 100 to 300: spread 200, so pad = 200 and the band is -100 to 500. 20 and 480 are outside the samples and outside PAD_MIN.
        val ex = nonceServer({ 100 + (it % 5) * 50 }, mapOf("/home" to 20, "/about" to 480))
        val (_, p) = run(ex, listOf("home", "about", "admin"))
        assertEquals(listOf("/admin"), p.paths())
    }
    @Test fun `a dead host fails calibration after the root pair alone`() = runTest {
        // Ruling R9: HEAD / and GET / each fail twice (one retry each), then the run ends; 4 requests and 4 gates, not 16.
        val requests = AtomicInteger(0); val gates = AtomicInteger(0)
        val control = object : RunControl {
            override suspend fun gate() { gates.incrementAndGet() }
            override val pauseEpoch: Int get() = 0
            override fun excused(startEpoch: Int) = false
        }
        val r = tool(Exchange { _, _ -> requests.incrementAndGet(); HttpResult.Failed(HttpFailure.Timeout) }, listOf("a", "b"))
            .run(target(), options(DirBruteOptions(concurrency = 1, extensions = listOf("php"))), control, CollectingProgress())
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("The server did not answer the first request", r.summary)
        assertEquals(4, requests.get()); assertEquals(4, gates.get())
    }
    @Test fun `a path echoing 200 is filtered`() = runTest {
        val ex = Exchange { _, path -> if (path == "/admin") resp(200, "A".repeat(3000)) else resp(200, "Not found: $path") }
        val (_, p) = run(ex, listOf("home", "a-much-longer-word", "admin"))
        assertEquals(listOf("/admin"), p.paths())
    }
    @Test fun `a catch all redirect is filtered and a real redirect is reported`() = runTest {
        val ex = Exchange { _, path -> if (path == "/admin") resp(301, "", listOf("Location" to "http://x/admin/")) else resp(302, "", listOf("Location" to "http://x/login")) }
        val (_, p) = run(ex, listOf("home", "admin", "contact"))
        assertEquals(listOf("/admin"), p.paths())
        assertEquals("301  0 B  to http://x/admin/", p.findings.first { it.value == "/admin" }.detail)
    }
    @Test fun `an extension group has its own baseline`() = runTest {
        // Bare unknowns 404; unknown .php paths answer 200 with a small page; /index.php is a real, larger page.
        val ex = Exchange { _, path -> when {
            path == "/index.php" -> resp(200, "P".repeat(2000))
            path.endsWith(".php") -> resp(200, "php handler")
            else -> resp(404, "")
        } }
        val (_, p) = run(ex, listOf("index", "home"), DirBruteOptions(extensions = listOf("php"), concurrency = 1))
        assertEquals(listOf("/index.php"), p.paths())
    }
    @Test fun `a head 404 get 200 server switches to get only and finds its paths`() = runTest {
        val ex = Exchange { method, path -> when {
            method == "HEAD" -> resp(404, "")
            path.startsWith("/admin") -> resp(200, "A".repeat(4000))
            else -> resp(200, "nf")
        } }
        val (r, p) = run(ex, listOf("home", "admin"))
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals(listOf("/admin"), p.paths())
        assertEquals("the switch is written back to optionsJson (plan review G8)", "GET_ONLY", p.options["methodUsed"])
    }
    @Test fun `a head and get disagreement on an unknown path alone switches to get only`() = runTest {
        // / answers alike to HEAD and GET, so only the random-path HEAD check can spot the HEAD 404 / GET 200 split.
        val ex = Exchange { method, path -> when {
            path == "/" -> resp(200, "home")
            method == "HEAD" -> resp(404, "")
            path == "/admin" -> resp(200, "A".repeat(4000))
            else -> resp(200, "nf")
        } }
        val (_, p) = run(ex, listOf("home", "admin"))
        assertEquals(listOf("/admin"), p.paths()); assertEquals("GET_ONLY", p.options["methodUsed"])
    }
    @Test fun `a head and get disagreement on the root alone switches to get only`() = runTest {
        // HEAD answers 404 everywhere and unknown paths 404 on GET too, so only HEAD / against GET / can spot the broken HEAD.
        val ex = Exchange { method, path -> when {
            method == "HEAD" -> resp(404, "")
            path == "/" -> resp(200, "home")
            path == "/admin" -> resp(200, "A".repeat(4000))
            else -> resp(404, "")
        } }
        val (_, p) = run(ex, listOf("home", "admin"))
        assertEquals(listOf("/admin"), p.paths()); assertEquals("GET_ONLY", p.options["methodUsed"])
    }
    @Test fun `a group with only three responses is calibrated from what it has`() = runTest {
        var gets = 0
        val ex = Exchange { method, path -> when {
            path == "/abcdef012345" && method == "GET" && gets++ < 4 -> HttpResult.Failed(HttpFailure.Refused)   // two samples fail both tries
            path == "/admin" -> resp(200, "A".repeat(4000))
            else -> resp(200, "nf")
        } }
        val (_, p) = run(ex, listOf("home", "admin"))
        assertEquals(listOf("/admin"), p.paths())
    }
    @Test fun `a failed calibration ends the run FAILED`() = runTest {
        val (r, _) = run(Exchange { _, _ -> HttpResult.Failed(HttpFailure.Timeout) }, listOf("a", "b"))
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("The server did not answer the first request", r.summary)
    }
    @Test fun `a known content length is preferred to the capped body for the band size (Task 5 review)`() {
        val capped = ByteArray(65_536)
        assertEquals(200_000L, NotFoundFilter.normalisedSize(HttpResponse(200, "x", emptyList(), capped, true, 200_000L), "/a"))
        assertEquals(70_000L, NotFoundFilter.normalisedSize(HttpResponse(200, "x", emptyList(), capped, true, 70_000L), "/a"))
        assertNull(NotFoundFilter.normalisedSize(HttpResponse(200, "x", emptyList(), capped, true, null), "/a"))
        assertEquals("chunked, not truncated: the body is the size", 6L, NotFoundFilter.normalisedSize(HttpResponse(200, "x", emptyList(), "ab/abc".toByteArray(), false, null), "/q"))
        assertEquals("path echoes are subtracted", 4L, NotFoundFilter.normalisedSize(HttpResponse(200, "x", emptyList(), "nf: /abc".toByteArray(), false, 8L), "/abc"))
    }
}
