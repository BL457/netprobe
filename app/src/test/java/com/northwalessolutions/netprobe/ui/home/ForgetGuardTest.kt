package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry.Work
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 9.1 item 10. */
class ForgetGuardTest {
    private val shown = listOf(TestHosts.host("10.0.3.5").copy(id = 5), TestHosts.host("10.0.3.6").copy(id = 6))

    @Test fun `a port scan or a tool run on a host of the shown network blocks Forget`() {
        assertTrue(ForgetGuard.workRunning(shown, mapOf(5L to Work.PORT_SCAN)))
        assertTrue(ForgetGuard.workRunning(shown, mapOf(6L to Work.TOOL)))
    }
    @Test fun `work on another network's host does not`() {
        assertFalse(ForgetGuard.workRunning(shown, mapOf(99L to Work.PORT_SCAN, 98L to Work.TOOL)))
        assertFalse(ForgetGuard.workRunning(shown, emptyMap()))
        assertFalse(ForgetGuard.workRunning(emptyList(), mapOf(5L to Work.PORT_SCAN)))
    }
    @Test fun `forget is refused while anything runs and proceeds otherwise`() {
        assertTrue(ForgetGuard.allowed(radarRunning = false, quickRunning = false, workRunning = false))
        assertFalse(ForgetGuard.allowed(radarRunning = true, quickRunning = false, workRunning = false))
        assertFalse(ForgetGuard.allowed(radarRunning = false, quickRunning = true, workRunning = false))
        assertFalse(ForgetGuard.allowed(radarRunning = false, quickRunning = false, workRunning = true))
    }
}
