package com.northwalessolutions.netprobe.netenv

import org.junit.Assert.*
import org.junit.Test

/** 3C spec 8.1 item 3 (D19, ruling 1, controller ruling: a /16 sweeps at most its /20). */
class HomeRangeTest {
    private fun ip(s: String) = Ipv4.parse(s)!!

    @Test fun `aggressive off equals sweepRange for every prefix and limit`() {
        val phone = ip("172.20.9.40")
        for (prefix in 8..30) for (limit in 20..24)
            assertEquals("/$prefix at /$limit", SubnetMath.sweepRange(phone, prefix, limit), HomeRange.of(phone, prefix, limit, aggressive = false))
    }
    @Test fun `aggressive on with a slash 16 sweeps the limit block that holds the phone`() {
        assertEquals(SweepRange(SubnetMath.of(ip("172.20.8.0"), 22), clamped = true, originalPrefix = 16), HomeRange.of(ip("172.20.9.40"), 16, 22, aggressive = true))
        val widest = HomeRange.of(ip("172.20.9.40"), 16, 20, aggressive = true)
        assertEquals("172.20.0.0/20", widest.subnet.toString()); assertTrue(widest.clamped); assertEquals(16, widest.originalPrefix)
    }
    @Test fun `a slash 20 at limit 22 gives the slash 22 holding the phone`() {
        assertEquals("10.20.36.0/22", HomeRange.of(ip("10.20.37.5"), 20, 22, aggressive = true).subnet.toString())
    }
    @Test fun `masks at or narrower than the limit are identical on and off`() {
        for (prefix in listOf(22, 23, 24, 25)) assertEquals("/$prefix", HomeRange.of(ip("10.0.3.20"), prefix, 22, false), HomeRange.of(ip("10.0.3.20"), prefix, 22, true))
        assertEquals("10.0.3.0/24", HomeRange.of(ip("10.0.3.20"), 24, 20, aggressive = true).subnet.toString())   // the Director's home, at every limit
    }
    @Test fun `aggressive on at limit 24 is the phone's slash 24, still marked clamped`() {
        val r = HomeRange.of(ip("172.20.9.40"), 16, 24, aggressive = true)
        assertEquals("172.20.9.0/24", r.subnet.toString()); assertTrue(r.clamped)
    }
}
