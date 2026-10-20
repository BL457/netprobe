package com.northwalessolutions.netprobe.ui.home

import org.junit.Assert.*
import org.junit.Test

/** spec 5.5 and 9.1 test 31 (Gruff m13, Q1). */
class StaleRunTest {
    @Test fun `seeds from the stored run only when the network id changes`() {
        assertEquals(500L, StaleRun.seed(previousNetworkId = null, networkId = 7, current = 0, lastRunAt = 500))
        assertEquals(0L, StaleRun.seed(previousNetworkId = 7, networkId = 8, current = 900, lastRunAt = null))   // also fixes the old cross-network bug
        assertEquals(0L, StaleRun.seed(previousNetworkId = 7, networkId = null, current = 900, lastRunAt = null))
    }
    @Test fun `a re-emission for the same network keeps an in-progress run`() {
        assertEquals(900L, StaleRun.seed(previousNetworkId = 7, networkId = 7, current = 900, lastRunAt = 500))
    }
    @Test fun `only a completed run is persisted`() {
        assertEquals(1_234L, StaleRun.toPersist(completed = true, runId = 1_234)); assertNull(StaleRun.toPersist(completed = false, runId = 1_234))
    }
}
