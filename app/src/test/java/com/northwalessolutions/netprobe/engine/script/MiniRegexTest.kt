package com.northwalessolutions.netprobe.engine.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniRegexTest {
    private fun find(pattern: String, text: String, flags: String = "", from: Int = 0): MiniRegex.Match? =
        MiniRegex.compile(pattern, MiniRegex.flagsOf(flags)).find(text, from, MiniRegex.Steps(5_000_000))

    private fun first(pattern: String, text: String, flags: String = ""): String? = find(pattern, text, flags)?.let { text.substring(it.start, it.end) }
    private fun group(pattern: String, text: String, n: Int, flags: String = ""): String? = find(pattern, text, flags)?.let { m -> if (m.starts[n] < 0) null else text.substring(m.starts[n], m.ends[n]) }

    @Test fun `literals dot and classes`() {
        assertEquals("abc", first("abc", "xxabcxx"))
        assertEquals("a-c", first("a.c", "a-c"))
        assertNull(first("a.c", "a\nc"))
        assertEquals("a\nc", first("a.c", "a\nc", "s"))
        assertEquals("b", first("[abc]", "xxb"))
        assertEquals("xyz", first("[^abc]+", "abxyzc"))
        assertEquals("m", first("[k-n]", "xxm"))
        assertEquals("-", first("[a\\-z]", "x-"))
        assertEquals("123", first("\\d+", "ab123cd"))
        assertEquals("ab_1", first("\\w+", "  ab_1 "))
        assertEquals(" \t", first("\\s+", "a \tb"))
        assertEquals("ab", first("[\\w]+", "ab-"))
        assertEquals("A", first("a", "A", "i"))
        assertEquals("Q", first("[a-z]", "Q", "i"))
    }

    @Test fun `quantifiers greedy and lazy`() {
        assertEquals("aaa", first("a+", "caaab"))
        assertEquals("a", first("a+?", "caaab"))
        assertEquals("", first("a*", "baa"))
        assertEquals("aa", first("a{2}", "aaa"))
        assertEquals("aaaa", first("a{2,}", "aaaa"))
        assertEquals("aaa", first("a{1,3}", "aaaa"))
        assertEquals("a", first("a{1,3}?", "aaaa"))
        assertEquals("ab", first("ab?", "abb"))
        assertEquals("<a><b>", first("<.+>", "<a><b>"))
        assertEquals("<a>", first("<.+?>", "<a><b>"))
        assertEquals("a{", first("a{", "a{"))
        assertEquals("a{x}", first("a{x}", "a{x}"))
    }

    @Test fun `anchors boundaries and alternation`() {
        assertEquals("ab", first("^ab", "abab"))
        assertNull(first("^b", "ab"))
        assertEquals("b", first("b$", "abb"))
        assertNull(first("^b", "a\nb"))
        assertEquals("b", first("^b", "a\nb", "m"))
        assertEquals("a", first("a$", "a\nb", "m"))
        assertEquals("cat", first("\\bcat\\b", "a cat sat"))
        assertNull(first("\\bcat\\b", "concatenate"))
        assertEquals("cat", first("\\Bcat", "concatenate"))
        assertEquals("dog", first("cat|dog", "hotdog"))
        assertEquals("b", first("a|b|", "b"))
        assertEquals("ab", first("a(?:b|c)", "xab"))
    }

    @Test fun `groups back references and lookahead`() {
        assertEquals("b", group("a(b)(c)", "abc", 1)); assertEquals("c", group("a(b)(c)", "abc", 2))
        assertNull(group("a(b)?c", "ac", 1))
        assertEquals("b", group("(a)|(b)", "b", 2))
        assertEquals("abab", first("(ab)\\1", "xababx"))
        assertEquals("aa", first("(a)\\1", "baab"))
        assertEquals("foo", first("foo(?=bar)", "foobar"))
        assertNull(first("foo(?=bar)", "foobaz"))
        assertEquals("foo", first("foo(?!bar)", "foobaz"))
        assertEquals("c", group("(?:a|b)(c)", "bc", 1))
        assertEquals("b", group("(a+)+?(b)", "aab", 2))
    }

    @Test fun `find continues from an offset and reports positions`() {
        val m = find("a", "banana", from = 2)!!
        assertEquals(3, m.start); assertEquals(4, m.end)
        assertNull(find("a", "banana", from = 6))
        assertEquals(6, find("", "banana", from = 6)!!.start)
    }

    @Test fun `a long text does not overflow the stack`() {
        val text = "a".repeat(600_000) + "b"
        assertEquals(600_001, first(".*b", text)!!.length)
        assertEquals(600_000, first("a+", text)!!.length)
        assertEquals(600_001, first("[ab]*", text)!!.length)
    }

    @Test fun `catastrophic patterns stop at the step limit`() {
        fun stops(pattern: String, text: String): Boolean =
            try { MiniRegex.compile(pattern, 0).find(text, 0, MiniRegex.Steps(1_000_000)); false } catch (_: MiniRegex.StepLimit) { true }
        assertTrue(stops("^(a+)+$", "a".repeat(40) + "b"))
        assertTrue(stops("(x+x+)+y", "x".repeat(60)))
        assertTrue(stops("(a|a)*b", "a".repeat(60)))
        assertTrue(stops("(.*)*z", "a".repeat(40)))
    }

    @Test fun `bad patterns and flags are syntax errors`() {
        for (p in listOf("(", ")", "[a", "a{2,1}", "*a", "+", "a**", "\\q", "(?<n>a)", "\\", "a{99999999}", "(?:", "(".repeat(60) + "a" + ")".repeat(60))) {
            val e = runCatching { MiniRegex.compile(p, 0) }.exceptionOrNull()
            assertTrue("$p -> $e", e is MiniRegex.SyntaxError)
        }
        assertTrue(runCatching { MiniRegex.flagsOf("g") }.exceptionOrNull() is MiniRegex.SyntaxError)
        assertEquals(MiniRegex.I or MiniRegex.S, MiniRegex.flagsOf("is"))
    }
}
