package com.northwalessolutions.netprobe

import com.northwalessolutions.netprobe.data.RawResources
import org.junit.Assert.assertEquals
import org.junit.Test

class RawResourcesTest {
    @Test fun `lines trims drops blanks and comments`() {
        assertEquals(listOf("_http._tcp", "_ssh._tcp"), RawResources.lines(" _http._tcp \n\n# comment\n_ssh._tcp\n"))
    }
    @Test fun `bundled mdns types file has the appendix B list`() {
        val types = RawResources.lines(javaClass.getResource("/mdns_types.txt")!!.readText())
        assertEquals(30, types.size); assert(types.contains("_device-info._tcp")); assert(types.all { it.matches(Regex("^_[a-z0-9-]+\\._(tcp|udp)$")) })
    }
}
