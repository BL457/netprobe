package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.HttpFailure
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.tools.RunControl
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.TestControl
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.options
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.resp
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.sized
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.target
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.tool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

/** The recursive option: folder detection (redirect and probe), breadth-first levels, the caps, per-folder calibration and request counting. */
class DirBruteRecursiveTest {
    private val off = DirBruteOptions(baseline = false, concurrency = 1)
    private fun redirect(path: String) = resp(301, "", listOf("Location" to "http://10.0.3.9:5000$path/"))
    private fun seenList() = Collections.synchronizedList(mutableListOf<String>())

    @Test fun `a redirect to the path plus a slash marks a folder without any probe`() = runTest {
        val seen = seenList()
        val ex = Exchange { _, path -> seen += path; if (path == "/admin") redirect(path) else resp(404) }
        val p = CollectingProgress()
        tool(ex, listOf("admin", "nope")).run(target(), options(off), TestControl(), p)
        val f = p.findings.first { it.value == "/admin" }
        assertEquals("true", f.extra["folder"]); assertTrue(f.detail, f.detail!!.endsWith("  folder"))
        assertFalse(seen.contains("/admin/"))
        assertEquals(2, p.lastDone)
    }
    @Test fun `a redirect elsewhere is not a folder`() = runTest {
        val ex = Exchange { _, path -> if (path == "/old") resp(302, "", listOf("Location" to "http://10.0.3.9:5000/new")) else resp(404) }
        val p = CollectingProgress()
        tool(ex, listOf("old")).run(target(), options(off), TestControl(), p)
        assertNull(p.findings.first { it.value == "/old" }.extra["folder"])
    }
    @Test fun `a bare word that answers 200 is probed with a slash and a 403 there makes it a folder`() = runTest {
        val ex = Exchange { _, path -> when (path) { "/admin" -> resp(200, "page"); "/admin/" -> resp(403); "/readme" -> resp(200, "text"); else -> resp(404) } }
        val p = CollectingProgress()
        val r = tool(ex, listOf("admin", "readme")).run(target(), options(off.copy(recursive = true, maxDepth = 1)), TestControl(), p)
        assertEquals("true", p.findings.first { it.value == "/admin" }.extra["folder"])
        assertNull(p.findings.first { it.value == "/readme" }.extra["folder"])
        assertTrue(r.summary, r.summary.contains("1 folder"))
    }
    @Test fun `no probe is sent when recursive is off`() = runTest {
        val seen = seenList()
        val ex = Exchange { _, path -> seen += path; if (path == "/admin") resp(200, "page") else resp(403) }
        val p = CollectingProgress()
        val r = tool(ex, listOf("admin")).run(target(), options(off), TestControl(), p)
        assertFalse(seen.any { it.endsWith("/") })
        assertNull(p.findings.first { it.value == "/admin" }.extra["folder"])
        assertFalse(r.summary.contains("folder"))
    }
    @Test fun `probes only run for bare words, not for extension paths`() = runTest {
        val seen = seenList()
        val ex = Exchange { _, path -> seen += path; if (path == "/a.php") resp(200, "x") else resp(404) }
        tool(ex, listOf("a")).run(target(), options(off.copy(recursive = true, extensions = listOf("php"))), TestControl(), CollectingProgress())
        assertFalse(seen.contains("/a.php/"))
    }
    @Test fun `recursion finds a file in a folder and stops at the depth limit`() = runTest {
        val seen = seenList()
        val ex = Exchange { _, path ->
            seen += path
            when (path) { "/admin", "/admin/config" -> redirect(path); "/admin/config.php" -> resp(200, "<?php"); else -> resp(404) }
        }
        val words = listOf("admin", "config")
        val deep = DirBruteOptions(baseline = false, concurrency = 1, extensions = listOf("php"), recursive = true, maxDepth = 2)
        val p = CollectingProgress()
        val r = tool(ex, words).run(target(), options(deep), TestControl(), p)
        assertTrue(p.paths().contains("/admin/config.php"))
        assertTrue(seen.contains("/admin/config/admin"))                 // level 2 ran under /admin/config
        assertEquals(12, p.lastTotal); assertEquals(12, p.lastDone)     // 4 paths at each of three prefixes
        assertEquals("3 found in 12 paths, 2 folders", r.summary)

        seen.clear()
        val q = CollectingProgress()
        tool(ex, words).run(target(), options(deep.copy(maxDepth = 1)), TestControl(), q)
        assertTrue(q.paths().contains("/admin/config.php"))
        assertFalse(seen.any { it.startsWith("/admin/config/") })        // /admin/config is a folder at the last level: reported, not entered
        assertEquals(8, q.lastTotal)
        assertEquals("true", q.findings.first { it.value == "/admin/config" }.extra["folder"])
    }
    @Test fun `a recursive run reports the level it is on and a plain run does not`() = runTest {
        val levels = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val watching = object : CollectingProgress() { override fun depth(level: Int, maxDepth: Int) { levels += level to maxDepth } }
        val ex = Exchange { _, path -> if (path == "/admin") redirect(path) else resp(404) }
        tool(ex, listOf("admin")).run(target(), options(off.copy(recursive = true, maxDepth = 3)), TestControl(), watching)
        assertEquals(listOf(0 to 3, 1 to 3), levels)
        levels.clear()
        tool(ex, listOf("admin")).run(target(), options(off), TestControl(), watching)
        assertTrue(levels.isEmpty())
    }
    @Test fun `at most 50 folders per level are descended, all are still reported`() = runTest {
        val words = (1..60).map { "w%02d".format(it) }
        val prefixes = Collections.synchronizedSet(mutableSetOf<String>())
        val ex = Exchange { _, path ->
            val parts = path.trim('/').split('/')
            if (parts.size == 2) prefixes += parts[0]
            if (parts.size == 1) redirect(path) else resp(404)
        }
        val p = CollectingProgress()
        tool(ex, words).run(target(), options(off.copy(recursive = true, maxDepth = 2)), TestControl(), p)
        assertEquals(words.take(50).toSet(), prefixes)                   // sorted, so the first 50 are taken
        assertEquals(60, p.findings.count { it.extra["folder"] == "true" })
        assertEquals(60 + 50 * 60, p.lastTotal)
    }
    @Test fun `the total folder cap holds across levels`() {
        val q = FolderQueue(perLevel = 50, total = 120)
        for (level in 0..2) {
            for (i in 1..80) q.add("/l$level/f$i", level, maxDepth = 4)
            assertEquals(when (level) { 0 -> 50; 1 -> 50; else -> 20 }, q.takeNext().size)
        }
        assertEquals(240, q.hits())                                      // every folder is counted, only 120 queued
        q.add("/late", 3, 4); assertTrue(q.takeNext().isEmpty())
        val last = FolderQueue(); last.add("/x", 2, 2)
        assertTrue("a folder at the last level is not queued", last.takeNext().isEmpty()); assertEquals(1, last.hits())
        val dup = FolderQueue(); dup.add("/y", 0, 2); dup.add("/y", 0, 2)
        assertEquals(1, dup.hits()); assertEquals(1, dup.takeNext().size)
    }
    @Test fun `each folder is calibrated, so its own not-found page is filtered`() = runTest {
        // Inside /admin every unknown path answers a 200 page of 120 bytes; without per-folder calibration all of them would be findings.
        val ex = Exchange { _, path ->
            when {
                path == "/admin" -> redirect(path)
                path == "/admin/real.php" -> sized(200, 900)
                path.startsWith("/admin/") -> sized(200, 120)
                else -> resp(404)
            }
        }
        val p = CollectingProgress()
        val o = DirBruteOptions(baseline = true, concurrency = 1, extensions = listOf("php"), recursive = true, maxDepth = 1)
        tool(ex, listOf("admin", "real")).run(target(), options(o), TestControl(), p)
        assertEquals(listOf("/admin", "/admin/real.php"), p.paths())
    }
    @Test fun `with the filter off the folder's soft 404 is not hidden`() = runTest {
        val ex = Exchange { _, path -> if (path == "/admin") redirect(path) else if (path.startsWith("/admin/")) sized(200, 120) else resp(404) }
        val p = CollectingProgress()
        tool(ex, listOf("admin", "x")).run(target(), options(off.copy(recursive = true, maxDepth = 1)), TestControl(), p)
        assertTrue(p.paths().contains("/admin/x"))
    }
    @Test fun `a folder that stops answering is skipped and the total shrinks back`() = runTest {
        val ex = Exchange { _, path ->
            if (path.startsWith("/admin/")) HttpResult.Failed(HttpFailure.Refused) else if (path == "/admin") redirect(path) else resp(404)
        }
        val p = CollectingProgress()
        val r = tool(ex, listOf("admin", "x")).run(target(), options(off.copy(baseline = true, recursive = true, maxDepth = 1)), TestControl(), p)
        assertEquals(ToolRunStatus.DONE, r.status)
        assertEquals(2, p.lastTotal); assertEquals(2, p.lastDone)
    }
    @Test fun `probes count in the request total and the path total`() = runTest {
        val seen = seenList()
        val ex = Exchange { _, path -> seen += path; if (path == "/a" || path == "/b") resp(200, "page") else resp(404) }
        val p = CollectingProgress()
        val r = tool(ex, listOf("a", "b", "c")).run(target(), options(off.copy(recursive = true, maxDepth = 1)), TestControl(), p)
        assertEquals(2, seen.count { it == "/a/" || it == "/b/" })
        assertEquals(3 + 2, p.lastDone); assertEquals(3 + 2, p.lastTotal)
        assertTrue(r.summary, r.summary.startsWith("2 found in 5 paths"))
    }
    @Test fun `a probe goes through the gate like any request`() = runTest {
        var gates = 0
        val control = object : RunControl {
            override suspend fun gate() { gates++ }
            override val pauseEpoch: Int get() = 0
            override fun excused(startEpoch: Int) = false
        }
        val ex = Exchange { _, path -> when (path) { "/a" -> resp(200, "page"); "/a/" -> resp(403); else -> resp(404) } }
        tool(ex, listOf("a")).run(target(), options(off.copy(recursive = true, maxDepth = 1)), control, CollectingProgress())
        assertEquals(5, gates)                                           // HEAD and GET for /a, HEAD and GET for /a/, then HEAD for /a/a in the folder
    }
    @Test fun `the finding cap still ends a recursive run`() = runTest {
        val words = (1..600).map { "p$it" }
        val ex = Exchange { _, path -> sized(200, 100 + path.removePrefix("/p").trimEnd('/').toInt() * 100) }
        val r = tool(ex, words).run(target(), options(off.copy(recursive = true, maxDepth = 2)), TestControl(), CollectingProgress())
        assertEquals("Stopped at 500 findings", r.summary)
    }
    @Test fun `locations are compared by path only`() {
        assertTrue(DirBruteforceTool.redirectsToFolder(301, "https://other.host:8443/admin/", "/admin"))
        assertTrue(DirBruteforceTool.redirectsToFolder(308, "/admin/", "/admin"))
        assertTrue(DirBruteforceTool.redirectsToFolder(302, "http://h/admin/?x=1", "/admin"))
        assertFalse(DirBruteforceTool.redirectsToFolder(303, "/admin/", "/admin"))
        assertFalse(DirBruteforceTool.redirectsToFolder(301, "/admin", "/admin"))
        assertFalse(DirBruteforceTool.redirectsToFolder(301, "/admin/x/", "/admin"))
        assertFalse(DirBruteforceTool.redirectsToFolder(301, null, "/admin"))
    }
}
