package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.TestControl
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.options
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.sized
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.target
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.tool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Review Focus 2: more than 50 alike findings in the first 200 paths ends the run (spec 5.4.3). */
class DirBruteRunawayTest {
    @Test fun `more than fifty alike in the first two hundred ends the run`() = runTest {
        val p = CollectingProgress()
        val r = tool(Exchange { _, _ -> sized(200, 1024) }, (1..300).map { "p$it" }).run(target(), options(DirBruteOptions(baseline = false)), TestControl(), p)
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("Stopped: the server answers every path alike", r.summary)
        assertTrue("stopped near the 200 mark, at ${p.lastDone}", p.lastDone in 200..240)
    }
    @Test fun `fifty or fewer alike does not trigger the runaway`() = runTest {
        var i = 0
        val ex = Exchange { method, _ -> if (method == "GET" && i++ < 50) sized(200, 1024) else sized(404, 0) }
        val r = tool(ex, (1..250).map { "p$it" }).run(target(), options(DirBruteOptions(baseline = false, method = DirMethod.GET_ONLY, concurrency = 1)), TestControl(), CollectingProgress())
        assertEquals("50 found in 250 paths", r.summary)
    }
    /**
     * CHNAS (DSM 7, 10.0.3.59:5001) as Phase 3B QA saw it: every path with a dot segment answers 403 with the same 11939-byte page,
     * plain unknown paths answer 404, and the bundled lists put the dot entries first. With extensions php, html and txt the first
     * 200 attempts held 84 dot 403s and the run stopped as "answers every path alike". The dot twin bands that 403 as not-found.
     */
    private val chnasDotWords = listOf(".DS_Store", ".aws/credentials", ".bash_history", ".bashrc", ".config", ".env", ".env.bak", ".env.local",
        ".env.prod", ".git-credentials", ".git/HEAD", ".git/config", ".git/index", ".gitignore", ".htaccess", ".htpasswd", ".npmrc",
        ".ssh/id_rsa", ".ssh/known_hosts", ".svn/entries", ".well-known/security.txt")
    private val chnasReal = mapOf("/robots.txt" to 26, "/index.html" to 1810, "/webman/index.cgi.php" to 4200)
    private fun chnas(extensionCatchAll: Boolean) = Exchange { _, path -> when {
        path in chnasReal -> sized(200, chnasReal.getValue(path))
        hasDotSegment(path) -> sized(403, CHNAS_FORBIDDEN)
        extensionCatchAll && listOf(".php", ".html", ".txt").any { path.endsWith(it) } -> sized(403, CHNAS_FORBIDDEN)
        else -> sized(404, CHNAS_NOT_FOUND)
    } }
    private val chnasWords = chnasDotWords + listOf("robots", "index", "webman/index.cgi") + (1..120).map { "w$it" }

    @Test fun `a 403 page for every dot path is banded as not found, so the run completes and finds the real paths`() = runTest {
        val p = CollectingProgress()
        val r = tool(chnas(extensionCatchAll = false), chnasWords).run(target(), options(DirBruteOptions(extensions = listOf("php", "html", "txt"))), TestControl(), p)
        val total = chnasWords.size * 4
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("3 found in $total paths", r.summary)
        assertEquals(chnasReal.keys, p.paths().toSet())
    }
    @Test fun `a 403 catch all on every extension and dot path still lets the real 200 paths through`() = runTest {
        val p = CollectingProgress()
        val r = tool(chnas(extensionCatchAll = true), chnasWords).run(target(), options(DirBruteOptions(extensions = listOf("php", "html", "txt"))), TestControl(), p)
        assertEquals("3 found in ${chnasWords.size * 4} paths", r.summary); assertEquals(chnasReal.keys, p.paths().toSet())
    }
    @Test fun `a consistent not found answer never trips the runaway, because only unfiltered findings are sampled`() = runTest {
        // Every path, random or not, answers the same 403: calibration bands it in every group, so 300 alike answers are all hidden.
        val r = tool(Exchange { _, _ -> sized(403, CHNAS_FORBIDDEN) }, (1..300).map { "p$it" }).run(target(), options(DirBruteOptions()), TestControl(), CollectingProgress())
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("0 found in 300 paths", r.summary)
    }
    @Test fun `a catch all calibration cannot band still ends the run with calibration on`() = runTest {
        // Random paths 404 (the not-found class), but every word answers 200 alike: 200 has no band, so these are findings and alike.
        val p = CollectingProgress()
        val ex = Exchange { _, path -> if (path.contains("abcdef012345")) sized(404, 0) else sized(200, 1024) }
        val r = tool(ex, (1..300).map { "p$it" }).run(target(), options(DirBruteOptions()), TestControl(), p)
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("Stopped: the server answers every path alike", r.summary)
        assertTrue("stopped near the 200 mark, at ${p.lastDone}", p.lastDone in 200..240)
    }
    @Test fun `dot segment paths are grouped with the dot twin of their extension group`() {
        assertEquals(".", groupOf("/.env", "")); assertEquals(".php", groupOf("/.env.php", "php")); assertEquals(".", groupOf("/.git/HEAD", ""))
        assertEquals(".txt", groupOf("/a/.htaccess.txt", "txt")); assertEquals("php", groupOf("/index.php", "php")); assertEquals("", groupOf("/robots.txt", ""))
    }
    @Test fun `alike means within sixty four bytes, spread sizes never trigger`() {
        assertTrue(DirBruteforceTool.runaway((1..51).map { 200 to (1000L + it) }))
        assertFalse(DirBruteforceTool.runaway((1..51).map { 200 to (1000L + it * 100) }))
        assertFalse(DirBruteforceTool.runaway((1..50).map { 200 to 1000L }))
        assertTrue(DirBruteforceTool.runaway((1..51).map { 200 to null }))
    }
    private companion object { const val CHNAS_FORBIDDEN = 11_939; const val CHNAS_NOT_FOUND = 9_870 }
}
