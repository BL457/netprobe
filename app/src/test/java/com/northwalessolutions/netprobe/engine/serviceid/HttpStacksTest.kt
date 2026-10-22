package com.northwalessolutions.netprobe.engine.serviceid

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Phase 3E spec 5: path selection, the hard rule, the favicon fetch, all against a scripted fetcher (no network). */
class HttpStacksTest {
    private val calls = mutableListOf<String>()
    private var replies: Map<String, StackReply> = emptyMap()
    private val get = StackGet { path, _ -> calls += path; replies[path] }

    private fun rules(vararg lines: String): List<HttpStackRule> {
        val r = HttpStackRules.load("[" + lines.joinToString(",") + "]")
        assertTrue("fixture skipped: ${r.skipped.map { it.id + ": " + it.reason }}", r.skipped.isEmpty())
        return r.rules
    }
    private fun title(id: String, t: String, extra: String = "") =
        """{"id":"$id","all":[{"where":"title","pattern":"$t"}],"stack":"$id","sample":"$t"$extra}"""
    private fun pathOnly(id: String, path: String, body: String) =
        """{"id":"$id","stack":"$id","paths":[{"path":"$path","status":200,"body":"$body"}],"sample":"$body"}"""
    private fun page(title: String? = null, body: String = "") = StackSources(emptyList(), emptyList(), title, null, body, status = 200)
    private fun reply(status: Int, body: String) = StackReply(status, body.toByteArray())
    private fun run(rules: List<HttpStackRule>, src: StackSources, http: Boolean = true) = runBlocking { HttpStacks.run(rules, src, http, get) }

    @Test fun `a cheap hit with a hard flag names the stack and spends no path when it has none`() {
        val r = run(rules(title("a", "Alpha", ",\"hard\":true")), page("Alpha"))
        assertEquals("a", r.hard!!.stack); assertTrue(calls.isEmpty())
    }
    @Test fun `the first hard hit in file order sets the stack and every hit is kept`() {
        val r = run(rules(title("soft", "Alpha"), title("first", "Alpha", ",\"hard\":true"), title("second", "Alpha", ",\"hard\":true")), page("Alpha"))
        assertEquals("first", r.hard!!.rule.id); assertEquals(listOf("soft", "first", "second"), r.hits.map { it.rule.id })
    }
    @Test fun `no hard hit means no stack but the soft hits are kept`() {
        val r = run(rules(title("soft", "Alpha")), page("Alpha"))
        assertNull(r.hard); assertEquals(listOf("soft"), r.hits.map { it.rule.id })
    }
    @Test fun `a hit rule spends its own paths first, in file order, and a confirmed path is recorded`() {
        replies = mapOf("/api/" to reply(401, "401: Unauthorized"))
        val ha = title("ha", "Home", ""","paths":[{"path":"/api/","status":401,"body":"Unauthorized"}]""")
        val other = title("other", "Home", ""","paths":[{"path":"/other","status":200,"body":"x"}]""")
        val r = run(rules(pathOnly("probe-only", "/probe", "zzz"), ha, other), page("Home"))
        assertEquals(listOf("/api/", "/other"), calls.take(2)); assertEquals("/api/", r.hits.single { it.rule.id == "ha" }.confirmedAt)
        assertNull(r.hits.single { it.rule.id == "other" }.confirmedAt)
        assertTrue("ha is hard through its paths", r.hard!!.rule.id == "ha")
        assertTrue("a hard hit ended the path-only pass", "/probe" !in calls)
    }
    @Test fun `path only rules are tried when the port is http and nothing hard has hit, until three paths are spent`() {
        val r = run(rules(pathOnly("p1", "/1", "a"), pathOnly("p2", "/2", "b"), pathOnly("p3", "/3", "c"), pathOnly("p4", "/4", "d")), page())
        assertEquals(listOf("/1", "/2", "/3"), calls); assertEquals(3, r.pathsSpent); assertNull(r.hard)
    }
    @Test fun `a path only rule hits on its status and body and is hard`() {
        replies = mapOf("/2" to reply(200, "hello b world"), "/1" to reply(404, "a"))
        val r = run(rules(pathOnly("p1", "/1", "a"), pathOnly("p2", "/2", "b")), page())
        assertEquals("p2", r.hard!!.rule.id); assertEquals("/2", r.hard!!.confirmedAt)
    }
    @Test fun `path only rules are not tried when the port is not http`() {
        run(rules(pathOnly("p1", "/1", "a")), page(), http = false)
        assertTrue(calls.isEmpty())
    }
    @Test fun `path only rules are not tried after a hard hit`() {
        run(rules(title("hard", "Alpha", ",\"hard\":true"), pathOnly("p1", "/1", "a")), page("Alpha"))
        assertTrue(calls.isEmpty())
    }
    @Test fun `a path is fetched once even when two rules name it`() {
        replies = mapOf("/same" to reply(200, "x"))
        run(rules(pathOnly("p1", "/same", "y"), pathOnly("p2", "/same", "x")), page())
        assertEquals(listOf("/same"), calls)
    }
    @Test fun `the spend callback stops the fetching`() {
        val r = runBlocking { HttpStacks.run(rules(pathOnly("p1", "/1", "a")), page(), true, get, canSpend = { false }) }
        assertTrue(calls.isEmpty()); assertEquals(0, r.pathsSpent)
    }

    // ---- favicon ----
    private val fav = """{"id":"fav","all":[{"where":"favicon","pattern":"%d"}],"stack":"Fav","sample":{"favicon":"%d"}}"""
    @Test fun `the favicon is fetched once, only when a rule needs it, and does not count against the paths`() {
        val body = ByteArray(50) { it.toByte() }
        val hash = Favicon.shodanHash(body)
        replies = mapOf("/favicon.ico" to StackReply(200, body))
        val twice = fav.format(hash, hash).replace("\"fav\"", "\"fav2\"")
        val r = run(rules(fav.format(hash, hash), twice, pathOnly("p1", "/1", "a"), pathOnly("p2", "/2", "b"), pathOnly("p3", "/3", "c")), page())
        assertEquals(1, calls.count { it == "/favicon.ico" }); assertTrue(r.faviconFetched)
        assertEquals("Fav", r.hard!!.stack)
        // the favicon hit is hard, so the path only pass did not run
        assertEquals(listOf("/favicon.ico"), calls)
    }
    @Test fun `no favicon rule means no favicon fetch`() {
        val r = run(rules(title("a", "Alpha")), page("Alpha"))
        assertFalse(r.faviconFetched); assertTrue(calls.isEmpty())
    }
    @Test fun `a favicon rule with another condition that misses does not fetch the favicon`() {
        val both = """{"id":"both","all":[{"where":"title","pattern":"Beta"},{"where":"favicon","pattern":"5"}],"stack":"B","sample":{"title":"Beta","favicon":"5"}}"""
        val r = run(rules(both), page("Alpha"))
        assertFalse(r.faviconFetched); assertTrue(calls.isEmpty())
        replies = mapOf("/favicon.ico" to StackReply(200, byteArrayOf(1, 2, 3)))
        assertTrue(run(rules(both), page("Beta")).faviconFetched)
    }
    @Test fun `a favicon body over 64 KB is cut before it is hashed and a miss or error gives no hit`() {
        val big = ByteArray(100_000) { (it % 251).toByte() }
        replies = mapOf("/favicon.ico" to StackReply(200, big))
        val expect = Favicon.shodanHash(big.copyOf(HttpStacks.FAVICON_MAX_BYTES))
        assertEquals("Fav", run(rules(fav.format(expect, expect)), page()).hard!!.stack)
        calls.clear(); replies = mapOf("/favicon.ico" to StackReply(404, "no".toByteArray()))
        assertNull(run(rules(fav.format(1, 1)), page()).hard)
    }
}
