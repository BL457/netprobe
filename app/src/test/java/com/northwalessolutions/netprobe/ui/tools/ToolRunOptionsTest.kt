package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.tools.ToolOptions
import com.northwalessolutions.netprobe.engine.tools.ToolOptionsJson
import com.northwalessolutions.netprobe.engine.tools.ToolTarget
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirMethod
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** spec 7.1: optionsJson read back with defaults, the Options line of 7.5, malformed JSON tolerated. */
class ToolRunOptionsTest {
    private val t = ToolTarget(7, InetAddress.getByName("10.0.3.10") as Inet4Address, 5000, false, null)
    @Test fun `a brute force run reads back into the options line`() {
        val json = ToolOptionsJson.encode(t, ToolOptions.DirBrute(DirBruteOptions(), "common-short", 312))
        val o = ToolRunOptions.parse(json)
        assertTrue(o.isDirBrute)
        assertEquals("wordlist common-short (312 entries), extensions none, HEAD then GET, concurrency 8, timeout 3 s, codes 200 204 301 302 307 401 403, not-found filter on", o.optionsLine())
    }
    @Test fun `get only chosen, extensions, filter off`() {
        val json = ToolOptionsJson.encode(t, ToolOptions.DirBrute(DirBruteOptions(extensions = listOf("php", "html"), method = DirMethod.GET_ONLY, baseline = false), "api-paths", 137))
        assertEquals("wordlist api-paths (137 entries), extensions php html, GET only, concurrency 8, timeout 3 s, codes 200 204 301 302 307 401 403, not-found filter off",
            ToolRunOptions.parse(json).optionsLine())
    }
    @Test fun `a calibration switch reads as GET only because HEAD and GET disagree`() {
        val o = ToolRunOptions(wordlistId = "builtin:common-short", wordlistName = "common-short", wordlistEntries = 300, method = "HEAD_THEN_GET", methodUsed = "GET_ONLY",
            concurrency = 8, timeoutS = 3, statusCodes = listOf(200), baseline = true)
        assertTrue(o.optionsLine()!!.contains(", GET only (HEAD and GET disagree), "))
    }
    @Test fun `non brute runs have no options line and malformed json gives defaults`() {
        assertNull(ToolRunOptions.parse(ToolOptionsJson.encode(t.copy(serverName = "nas.lan"), ToolOptions.None)).optionsLine())
        assertEquals("nas.lan", ToolRunOptions.parse(ToolOptionsJson.encode(t.copy(serverName = "nas.lan"), ToolOptions.None)).serverName)
        assertEquals(ToolRunOptions(), ToolRunOptions.parse("{ not json"))
        assertEquals(mapOf("url" to "http://a/", "status" to "200"), FindingExtra.decode("{\"url\":\"http://a/\",\"status\":\"200\"}"))
        assertEquals(emptyMap<String, String>(), FindingExtra.decode(null)); assertEquals(emptyMap<String, String>(), FindingExtra.decode("x"))
    }
}
