package com.northwalessolutions.netprobe.engine.tools

import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class ToolJsonTest {
    private val t = ToolTarget(7, InetAddress.getByName("10.0.3.9") as Inet4Address, 443, tls = true, serverName = "chnas.local")
    @Test fun `options json always carries tls and serverName`() {
        assertEquals("{\"tls\":true,\"serverName\":\"chnas.local\"}", ToolOptionsJson.encode(t, ToolOptions.None))
        assertEquals("{\"tls\":true,\"serverName\":null}", ToolOptionsJson.encode(t.copy(serverName = null), ToolOptions.None))
    }
    @Test fun `withOption adds or replaces one key and leaves malformed json alone`() {
        assertEquals("{\"tls\":false,\"methodUsed\":\"GET_ONLY\"}", ToolOptionsJson.withOption("{\"tls\":false}", "methodUsed", "GET_ONLY"))
        assertEquals("{\"methodUsed\":\"GET_ONLY\"}", ToolOptionsJson.withOption("{\"methodUsed\":\"HEAD_THEN_GET\"}", "methodUsed", "GET_ONLY"))
        assertEquals("nope", ToolOptionsJson.withOption("nope", "a", "b"))
    }
    @Test fun `extra json is url then the per kind fields, null when empty`() {
        assertNull(FindingExtraJson.encode(Finding(FindingKind.TITLE, "x")))
        assertEquals("{\"url\":\"http://a/\",\"status\":\"200\"}", FindingExtraJson.encode(Finding(FindingKind.PATH, "/", url = "http://a/", extra = mapOf("status" to "200"))))
    }
}
