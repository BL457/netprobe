package com.northwalessolutions.netprobe.ui.settings

import com.northwalessolutions.netprobe.netenv.CidrParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtendedCopyTest {
    @Test fun `live cap line uses 25 s per range`() {
        assertEquals("At most 1 extra /24 network (about 1 minute)", ExtendedCopy.capLine(1))
        assertEquals("At most 4 extra /24 networks (about 2 minutes)", ExtendedCopy.capLine(4))
        assertEquals("At most 16 extra /24 networks (about 7 minutes)", ExtendedCopy.capLine(16))
    }
    @Test fun `entries beyond the cap are marked by their slash 24 units`() {
        val list = listOf("10.0.4.0/24", "10.0.8.0/23", "10.0.20.0/24").map { CidrParser.subnetOf(it)!! }
        assertEquals(listOf(false, false, true), ManualCap.beyondCap(list, 3))
        assertEquals(listOf(false, true, true), ManualCap.beyondCap(list, 2))
        assertEquals(listOf(false, false, false), ManualCap.beyondCap(list, 16))
    }
    @Test fun `title names the network`() { assertEquals("Manual ranges for Home", ExtendedCopy.manualTitle("Home")) }
}
