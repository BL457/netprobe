package com.northwalessolutions.netprobe.engine.portscan

import org.junit.Assert.*
import org.junit.Test

class ScanProfileTest {
    @Test fun `quick has 115 unique ports in range`() {
        val q = ScanProfile.Quick.ports
        assertEquals(115, q.size); assertEquals(q.size, q.toSet().size)
        assertTrue(q.all { it in 1..65535 })
        assertTrue(q.containsAll(listOf(22, 80, 443, 445, 3389, 5900, 8080, 62078, 8123, 32400)))
    }
    @Test fun `full is 1 to 65535`() {
        assertEquals(65535, ScanProfile.Full.ports.size); assertEquals(1, ScanProfile.Full.ports.first()); assertEquals(65535, ScanProfile.Full.ports.last())
    }
    @Test fun `parser accepts lists and ranges and sorts and dedupes`() {
        assertEquals(listOf(22, 80, 8000, 8001, 8002), ProfileParser.parse(" 80, 22,8000-8002,80 ").getOrThrow().ports)
    }
    @Test fun `parser rejects bad input with a message`() {
        for (bad in listOf("", "abc", "0", "65536", "80-22", "22,,80", "1-70000", "-5")) {
            val r = ProfileParser.parse(bad)
            assertTrue("should reject '$bad'", r.isFailure)
            assertFalse(r.exceptionOrNull()!!.message.isNullOrBlank())
        }
    }
    @Test fun `parser caps a range at 65535 entries not more`() {
        assertEquals(65535, ProfileParser.parse("1-65535").getOrThrow().ports.size)
    }
}
