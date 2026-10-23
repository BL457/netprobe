package com.northwalessolutions.netprobe.engine.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptUrlTest {
    private fun parse(s: String) = ScriptUrl.parse(s)
    private fun refused(s: String) { assertTrue("accepted: $s", runCatching { parse(s) }.exceptionOrNull() is ScriptHostError) }

    @Test fun `a plain url parses to host port and path`() {
        assertEquals(ScriptUrl(false, "10.0.0.5", 80, "/"), parse("http://10.0.0.5"))
        assertEquals(ScriptUrl(true, "example.test", 8443, "/a/b?c=d"), parse("HTTPS://Example.TEST:8443/a/b?c=d#frag"))
        assertEquals(ScriptUrl(false, "h", 80, "/?q=1"), parse("http://h?q=1"))
        assertEquals(ScriptUrl(false, "::1", 8080, "/"), parse("http://[::1]:8080/"))
        assertEquals("http://10.0.0.5:81/x", parse("http://10.0.0.5:81/x").text)
        assertEquals("10.0.0.5", parse("http://10.0.0.5:80/").hostHeader)
        assertEquals("[::1]:81", parse("http://[::1]:81/").hostHeader)
    }

    @Test fun `non ascii paths are percent encoded and spaces are refused`() {
        assertEquals("/%C3%A9", parse("http://h/\u00e9").pathAndQuery)
        assertEquals("/%F0%9F%98%80", parse("http://h/\uD83D\uDE00").pathAndQuery)
        refused("http://h/a b")
    }

    @Test fun `confusing authorities are refused`() {
        for (u in listOf("ftp://h/", "http:/h/", "//h/", "http://", "http:///x", "http://user@h/", "http://h@evil/", "http://h\\@evil/", "http://h:/", "http://h:0/", "http://h:65536/",
            "http://h:8a/", "http://h:99999/", "http://[::1/", "http://[::1]x/", "http://h /", "http://h\u0000/", "http://h/\r\nX: y", "javascript:alert(1)", "")) refused(u)
    }

    @Test fun `redirect locations resolve strictly`() {
        val base = parse("http://10.0.0.5:8080/a/b?x=1")
        assertEquals("http://10.0.0.6/z", base.resolve("http://10.0.0.6/z").text)
        assertEquals("http://10.0.0.6/z", base.resolve("//10.0.0.6/z").text)
        assertEquals("http://10.0.0.5:8080/c", base.resolve("/c").text)
        assertEquals("http://10.0.0.5:8080/a/c", base.resolve("c").text)
        assertEquals("http://10.0.0.5:8080/a/b?y=2", base.resolve("?y=2").text)
        assertEquals(base, base.resolve(""))
        for (loc in listOf("ftp://x/", "http://u@h/", "javascript:alert(1)//x://", "http://h:99999/")) {
            assertTrue(loc, runCatching { base.resolve(loc) }.exceptionOrNull() is ScriptHostError)
        }
    }
}
