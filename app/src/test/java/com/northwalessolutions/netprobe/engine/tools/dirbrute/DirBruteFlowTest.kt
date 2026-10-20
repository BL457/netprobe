package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.HttpFailure
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.common.RateBucket
import com.northwalessolutions.netprobe.engine.tools.FindingKind
import com.northwalessolutions.netprobe.engine.tools.RunControl
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.TestControl
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.options
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.resp
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.sized
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.target
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.tool
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** spec 5.2 and 5.3: report-set filtering, fallback, retries, the abort, the cap, order, encoding, load, the rate ceiling and the excused rule. */
class DirBruteFlowTest {
    private val off = DirBruteOptions(baseline = false, concurrency = 1)

    @Test fun `only report-set statuses become findings, with url, extra and hints`() = runTest {
        val ex = Exchange { _, path -> when (path) { "/admin" -> resp(200, "0123456789"); "/secret" -> resp(403, "denied"); else -> resp(404) } }
        val p = CollectingProgress()
        val r = tool(ex, listOf("admin", "secret", "nope")).run(target(), options(off), TestControl(), p)
        assertEquals(listOf("/admin", "/secret"), p.paths()); assertEquals("2 found in 3 paths", r.summary)
        val admin = p.findings.first { it.value == "/admin" }
        assertEquals("200  10 B", admin.detail); assertEquals("http://10.0.3.9:5000/admin", admin.url)
        assertEquals(mapOf("status" to "200", "size" to "10"), admin.extra)
        assertEquals(listOf("http.path=/admin"), p.findings.filter { it.kind == FindingKind.HINT }.map { it.value })   // 403 gets no hint
    }
    @Test fun `extension order and the path total`() = runTest {
        val seen = mutableListOf<String>()
        val ex = Exchange { _, path -> seen += path; resp(404) }
        val p = CollectingProgress()
        tool(ex, listOf("a"), count = 1).run(target(), options(off.copy(extensions = listOf("php", "html"))), TestControl(), p)
        assertEquals(listOf("/a", "/a.php", "/a.html"), seen); assertEquals(3, p.lastTotal)
    }
    @Test fun `head 405 re-issues as get and after three switches to get only`() = runTest {
        val heads = AtomicInteger(0); val gets = AtomicInteger(0)
        val ex = Exchange { method, _ -> if (method == "HEAD") { heads.incrementAndGet(); resp(405) } else { gets.incrementAndGet(); resp(404) } }
        val p = CollectingProgress()
        tool(ex, (1..10).map { "p$it" }).run(target(), options(off), TestControl(), p)
        assertEquals(3, heads.get()); assertEquals(10, gets.get())
        assertNull("spec 5.2: only the calibration switch is written to methodUsed", p.options["methodUsed"])
    }
    @Test fun `head 501 also falls back`() = runTest {
        val ex = Exchange { method, path -> if (method == "HEAD") resp(501) else if (path == "/admin") resp(200, "x") else resp(404) }
        val p = CollectingProgress()
        tool(ex, listOf("admin")).run(target(), options(off), TestControl(), p)
        assertEquals(listOf("/admin"), p.paths())
    }
    @Test fun `a timeout is retried once then counts as an error`() = runTest {
        val calls = AtomicInteger(0)
        val ex = Exchange { _, path -> if (path == "/x") { calls.incrementAndGet(); HttpResult.Failed(HttpFailure.Timeout) } else resp(404) }
        val p = CollectingProgress()
        val r = tool(ex, listOf("x", "y")).run(target(), options(off), TestControl(), p)
        assertEquals(2, calls.get()); assertEquals(1, p.lastErrors); assertEquals("0 found in 2 paths, 1 error", r.summary)
    }
    @Test fun `refused and malformed are not retried`() = runTest {
        val calls = AtomicInteger(0)
        val ex = Exchange { _, _ -> calls.incrementAndGet(); HttpResult.Failed(HttpFailure.Malformed) }
        tool(ex, listOf("x")).run(target(), options(off), TestControl(), CollectingProgress())
        assertEquals(1, calls.get())
    }
    @Test fun `an excused failure is retried after the resume and never counted`() = runTest {
        val control = TestControl(); var first = true
        val ex = Exchange { _, path ->
            // A pause began mid-request. Refused is never retried by the ordinary rule, so only the excused rule can save this path.
            if (path == "/x" && first) { first = false; control.epoch++; HttpResult.Failed(HttpFailure.Refused) }
            else if (path == "/x") resp(200, "hello") else resp(404)
        }
        val p = CollectingProgress()
        val r = tool(ex, listOf("x")).run(target(), options(off), control, p)
        assertEquals(listOf("/x"), p.paths()); assertEquals(0, p.lastErrors); assertEquals("1 found in 1 path", r.summary)
    }
    @Test fun `fifty consecutive counted failures end the run FAILED`() = runTest {
        val r = tool(Exchange { _, _ -> HttpResult.Failed(HttpFailure.Refused) }, (1..100).map { "p$it" }).run(target(), options(off), TestControl(), CollectingProgress())
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("The server stopped answering after 50 paths", r.summary)
    }
    @Test fun `the five hundred finding cap ends the run DONE`() = runTest {
        var n = 0
        val ex = Exchange { method, _ -> if (method == "HEAD") sized(200, 0) else sized(200, (n++ % 400) * 100 + 10) }   // sizes spread, so no runaway
        val r = tool(ex, (1..600).map { "p$it" }).run(target(), options(off), TestControl(), CollectingProgress())
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("Stopped at 500 findings", r.summary)
    }
    @Test fun `paths are percent encoded`() = runTest {
        val seen = mutableListOf<String>()
        tool(Exchange { _, path -> seen += path; resp(404) }, listOf("a b", "c%20d", "e/f", "caf\u00e9")).run(target(), options(off), TestControl(), CollectingProgress())
        assertEquals(listOf("/a%20b", "/c%20d", "/e/f", "/caf%C3%A9"), seen)
    }
    @Test fun `after an injected timeout the in-flight peak drops to the halved limit`() = runTest {
        // Eight workers always exist, so only the limiter can hold the peak at 4 once the first timeout halves it (plan review G12).
        // Every fifth path times out once (its retry succeeds); each timeout resets the recovery count, and the fixed clock keeps
        // every later timeout in the first window, so the limit stays at 4 for the rest of the run.
        val inFlight = AtomicInteger(0); val peakBefore = AtomicInteger(0); val peakAfter = AtomicInteger(0)
        val timeouts = AtomicInteger(0); val startsAfter = AtomicInteger(0); val failedOnce = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val ex = Exchange { _, path ->
            val n = inFlight.incrementAndGet()
            if (timeouts.get() == 0) peakBefore.updateAndGet { maxOf(it, n) }
            else if (startsAfter.incrementAndGet() > 1) peakAfter.updateAndGet { maxOf(it, n) }   // the first start after was admitted at 8
            try {
                yield(); yield()
                if (path.removePrefix("/p").toInt() % 5 == 0 && failedOnce.add(path)) { timeouts.incrementAndGet(); HttpResult.Failed(HttpFailure.Timeout) }
                else resp(404)
            } finally { inFlight.decrementAndGet() }
        }
        val p = CollectingProgress()
        val r = tool(ex, (1..120).map { "p$it" }).run(target(), options(DirBruteOptions(baseline = false, method = DirMethod.GET_ONLY, concurrency = 8)), TestControl(), p)
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals(0, p.lastErrors)
        assertEquals("all eight run before the first timeout", 8, peakBefore.get())
        assertTrue("peak after the halving ${peakAfter.get()}", peakAfter.get() in 1..4)
    }
    @Test fun `the rate ceiling is taken per request, so a head 200 catch all stays at one hundred a second`() = runTest {
        // HEAD 200 on every path means every path sends HEAD and GET (plan review G3). A gate per path would let 200 a second through.
        val bucket = RateBucket { testScheduler.currentTime }
        val control = object : RunControl {
            override suspend fun gate() { while (true) { val d = bucket.acquireDelayMs(); if (d == 0L) return; delay(d) } }
            override val pauseEpoch: Int get() = 0
            override fun excused(startEpoch: Int) = false
        }
        val stamps = java.util.Collections.synchronizedList(mutableListOf<Long>()); var n = 0
        val ex = Exchange { method, _ ->
            stamps += testScheduler.currentTime
            if (method == "HEAD") sized(200, 0) else sized(200, (n++ % 400) * 100 + 10)   // sizes spread, so no runaway
        }
        tool(ex, (1..300).map { "p$it" }).run(target(), options(DirBruteOptions(baseline = false, concurrency = 16)), control, CollectingProgress())
        assertEquals(600, stamps.size)
        assertTrue("first second ${stamps.count { it < 1000 }}", stamps.count { it < 1000 } <= 110)   // the window plus the initial burst
        for (w in 1L..stamps.max() / 1000) {
            val inWindow = stamps.count { it >= w * 1000 && it < (w + 1) * 1000 }
            assertTrue("window $w: $inWindow", inWindow <= 100)
        }
    }
    @Test fun `a server that times out above two concurrent completes with no counted errors once the limit drops`() = runTest {
        val inFlight = AtomicInteger(0)
        val ex = Exchange { _, _ ->
            val n = inFlight.incrementAndGet()
            try { yield(); if (n > 2) HttpResult.Failed(HttpFailure.Timeout) else resp(404) } finally { inFlight.decrementAndGet() }
        }
        var tick = 0L                                    // every limiter reading is a new window, so each counted timeout halves
        val t = DirBruteforceTool({ _, _ -> ex }, { LoadedWordlist("builtin:common-short", "common-short", 40) { c -> (1..40).forEach { c("p$it") } } },
            { "abcdef012345" }, clock = { tick += 10_000; tick })
        val p = CollectingProgress()
        val r = t.run(target(), options(DirBruteOptions(baseline = false, concurrency = 8)), TestControl(), p)
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals(0, p.lastErrors); assertEquals(40, p.lastDone)
    }
    @Test fun `a get timeout retries the get alone, never re-sending the head`() = runTest {
        val sent = mutableListOf<String>(); var getFailed = false
        val ex = Exchange { method, path ->
            sent += "$method $path"
            if (method == "GET" && !getFailed) { getFailed = true; HttpResult.Failed(HttpFailure.Timeout) } else resp(200, "x")
        }
        val p = CollectingProgress()
        tool(ex, listOf("a")).run(target(), options(off), TestControl(), p)
        assertEquals(listOf("HEAD /a", "GET /a", "GET /a"), sent); assertEquals(listOf("/a"), p.paths()); assertEquals(0, p.lastErrors)
    }
    @Test fun `a head 405 counts once toward the switch even when its get is retried`() = runTest {
        // Every GET times out once, then answers 404. Each path's 405 must count once, so the switch comes after /p3, not /p2.
        val heads = mutableListOf<String>(); val failedOnce = mutableSetOf<String>()
        val ex = Exchange { method, path ->
            if (method == "HEAD") { heads += path; resp(405) }
            else if (failedOnce.add(path)) HttpResult.Failed(HttpFailure.Timeout) else resp(404)
        }
        tool(ex, (1..5).map { "p$it" }).run(target(), options(off), TestControl(), CollectingProgress())
        assertEquals(listOf("/p1", "/p2", "/p3"), heads)
    }
    @Test fun `a deleted list falls back to common-short and records what ran`() = runTest {
        val t = DirBruteforceTool({ _, _ -> Exchange { _, _ -> resp(404) } },
            { LoadedWordlist("builtin:common-short", "common-short", 2) { c -> c("a"); c("b") } }, { "abcdef012345" }, { 0L })
        val p = CollectingProgress()
        val r = t.run(target(), options(off.copy(wordlistId = "user:gone")), TestControl(), p)
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("Wordlist not found, common-short used, 0 found in 2 paths", r.summary)
        assertEquals(mapOf("wordlistId" to "builtin:common-short", "wordlistName" to "common-short", "wordlistEntries" to "2"), p.options)
    }
    @Test fun `the chosen list records nothing extra`() = runTest {
        val p = CollectingProgress()
        val r = tool(Exchange { _, _ -> resp(404) }, listOf("a")).run(target(), options(off), TestControl(), p)
        assertEquals("0 found in 1 path", r.summary); assertTrue(p.options.isEmpty())
    }
    @Test fun `no list at all fails with its own text`() = runTest {
        val t = DirBruteforceTool({ _, _ -> Exchange { _, _ -> resp(404) } }, { null }, { "abcdef012345" }, { 0L })
        val r = t.run(target(), options(off), TestControl(), CollectingProgress())
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("Failed: Wordlist not found", r.summary)
    }
}
