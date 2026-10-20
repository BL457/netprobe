package com.northwalessolutions.netprobe.ui.detail

import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry.Work
import org.junit.Assert.*
import org.junit.Test

/** spec 4.3: Remove, Detail port scans and tool starts against the real registry. */
class DetailBusyRulesTest {
    private val host = 7L
    private fun registry(work: Work?): Map<Long, Work> {
        val r = HostBusyRegistry()
        if (work != null) assertTrue(r.tryClaim(host, work))
        r.tryClaim(99, Work.TOOL)   // another host's work never matters
        return r.busy.value
    }

    @Test fun `remove is refused only by a tool run`() {
        assertFalse(DetailBusyRules.removeAllowed(registry(Work.TOOL), host))
        assertTrue(DetailBusyRules.removeAllowed(registry(Work.PORT_SCAN), host))
        assertTrue(DetailBusyRules.removeAllowed(registry(null), host))
    }
    @Test fun `a port scan is refused by both kinds of work, each with its own words`() {
        assertEquals("A tool is running on this device", DetailBusyRules.scanRefusal(registry(Work.TOOL), host))
        assertEquals("Wait for the port scan to finish", DetailBusyRules.scanRefusal(registry(Work.PORT_SCAN), host))
        assertNull(DetailBusyRules.scanRefusal(registry(null), host))
    }
    @Test fun `a tool start is refused by both kinds of work`() {
        assertEquals("A tool is already running on this device", DetailBusyRules.toolRefusal(registry(Work.TOOL), host))
        assertEquals("Wait for the port scan to finish", DetailBusyRules.toolRefusal(registry(Work.PORT_SCAN), host))
        assertNull(DetailBusyRules.toolRefusal(registry(null), host))
    }
}
