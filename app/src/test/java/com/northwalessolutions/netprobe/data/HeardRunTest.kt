package com.northwalessolutions.netprobe.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 3C spec 3.4: which lastDiscoveryRun a heard write leaves on the row. */
class HeardRunTest {
    @Test fun `a new row takes the run for new rows`() {
        assertEquals(9L, HeardRun.runId(null, 9))
    }
    @Test fun `an existing visible row keeps its own run, so a heard packet never undims a device the latest scan missed`() {
        assertEquals(3L, HeardRun.runId(TestHosts.host().copy(lastDiscoveryRun = 3), 9))
    }
    @Test fun `a manual row being promoted takes the run for new rows`() {
        assertEquals(9L, HeardRun.runId(TestHosts.host().copy(lastDiscoveryRun = 0, manualOnly = true), 9))
    }
}
