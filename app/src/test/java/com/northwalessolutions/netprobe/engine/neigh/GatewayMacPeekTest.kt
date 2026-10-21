package com.northwalessolutions.netprobe.engine.neigh

import com.northwalessolutions.netprobe.netenv.Ipv4
import org.junit.Assert.*
import org.junit.Test

/** Final review S5: reading the gateway MAC for a listening session sends nothing. */
class GatewayMacPeekTest {
    private val gw = Ipv4.parse("10.0.3.1")!!
    @Test fun `peek reads the table and never connects`() {
        var dumps = 0
        val mac = GatewayMac.peek(gw) { dumps++; Result.success(listOf(NeighbourEntry("10.0.3.1", "AA:BB:CC:DD:EE:01", "REACHABLE"))) }
        assertEquals("aa:bb:cc:dd:ee:01", mac); assertEquals(1, dumps)
    }
    @Test fun `peek is null for no gateway, a failed dump or a missing entry`() {
        assertNull(GatewayMac.peek(null) { Result.success(emptyList()) })
        assertNull(GatewayMac.peek(gw) { Result.failure(IllegalStateException("denied")) })
        assertNull(GatewayMac.peek(gw) { Result.success(emptyList()) })
    }
}
