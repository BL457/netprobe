package com.northwalessolutions.netprobe.engine.tools

import org.junit.Assert.*
import org.junit.Test

/** spec 4.3: one work holds a host at a time; release by the wrong work is a no-op. */
class HostBusyRegistryTest {
    private val r = HostBusyRegistry()
    @Test fun `claim then a second claim is refused until release`() {
        assertTrue(r.tryClaim(7, HostBusyRegistry.Work.TOOL))
        assertFalse(r.tryClaim(7, HostBusyRegistry.Work.PORT_SCAN))
        assertEquals(HostBusyRegistry.Work.TOOL, r.busy.value[7])
        r.release(7, HostBusyRegistry.Work.TOOL)
        assertNull(r.busy.value[7]); assertTrue(r.tryClaim(7, HostBusyRegistry.Work.PORT_SCAN))
    }
    @Test fun `release by the wrong work does nothing`() {
        assertTrue(r.tryClaim(7, HostBusyRegistry.Work.TOOL))
        r.release(7, HostBusyRegistry.Work.PORT_SCAN)
        assertEquals(HostBusyRegistry.Work.TOOL, r.busy.value[7])
    }
    @Test fun `different hosts are independent`() {
        assertTrue(r.tryClaim(1, HostBusyRegistry.Work.TOOL)); assertTrue(r.tryClaim(2, HostBusyRegistry.Work.PORT_SCAN))
        assertEquals(2, r.busy.value.size)
    }
}
