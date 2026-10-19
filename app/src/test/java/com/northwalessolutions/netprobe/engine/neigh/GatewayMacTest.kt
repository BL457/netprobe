package com.northwalessolutions.netprobe.engine.neigh

import org.junit.Assert.*
import org.junit.Test

class GatewayMacTest {
    private val table = listOf(NeighbourEntry("10.0.3.7", "aa:aa:aa:aa:aa:aa", "REACHABLE"), NeighbourEntry("10.0.3.1", "E0:63:DA:26:F1:37", "STALE"))
    @Test fun `picks the gateway entry`() { assertEquals("e0:63:da:26:f1:37", GatewayMac.pick(table, "10.0.3.1")) }
    @Test fun `no entry is null`() { assertNull(GatewayMac.pick(table, "10.0.3.254")); assertNull(GatewayMac.pick(emptyList(), "10.0.3.1")) }
}
