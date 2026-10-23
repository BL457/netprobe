package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import org.junit.Assert.assertEquals
import com.northwalessolutions.netprobe.engine.tools.TestRegistry
import org.junit.Test

/** 3C spec 8.1 item 23. */
class ToolResultsRouteTest {
    private val reg = TestRegistry.registry()
    private fun runWith(route: String?) = ToolRunEntity(id = 3, hostId = 7, toolId = "tls-certificate", port = 443, startedAt = 0, finishedAt = 1,
        status = ToolRunStatus.DONE, optionsJson = "{}", summary = "unifi.local, expires 3 Jan 2027", route = route)

    @Test fun `the header names the stored route and shows nothing for a Device-screen run`() {
        val cases = listOf("WIFI" to "via Wi-Fi", "ETHERNET" to "via Ethernet", "OTHER" to "via VPN or other route", null to null, "SOMETHING" to null)
        for ((stored, expected) in cases) {
            val r = runWith(stored)
            assertEquals("stored $stored", expected, ToolResultsModel.ofRows(reg, r, listOf(r), "1.1.1.1", emptyList(), null, 10).route)
        }
    }
}
