package com.northwalessolutions.netprobe.ui.settings

import com.northwalessolutions.netprobe.netenv.CidrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExtendedCopyTest {
    @Test fun `live cap line uses 16 s per range, measured on the Pixel 7 Pro`() {
        assertEquals("At most 1 extra /24 network (about 1 minute)", ExtendedCopy.capLine(1))
        assertEquals("At most 4 extra /24 networks (about 2 minutes)", ExtendedCopy.capLine(4))
        assertEquals("At most 3 extra /24 networks (about 1 minute)", ExtendedCopy.capLine(3))
        assertEquals("At most 16 extra /24 networks (about 5 minutes)", ExtendedCopy.capLine(16))
    }
    @Test fun `entries beyond the cap are marked by their slash 24 units`() {
        val list = listOf("10.0.4.0/24", "10.0.8.0/23", "10.0.20.0/24").map { CidrParser.subnetOf(it)!! }
        assertEquals(listOf(false, false, true), ManualCap.beyondCap(list, 3))
        assertEquals(listOf(false, true, true), ManualCap.beyondCap(list, 2))
        assertEquals(listOf(false, false, false), ManualCap.beyondCap(list, 16))
    }
    @Test fun `title names the network`() { assertEquals("Manual ranges for Home", ExtendedCopy.manualTitle("Home")) }
    @Test fun `aggressive scan and smart discovery copy is the spec 7_2 table`() {
        assertEquals("Aggressive scan", ExtendedCopy.TITLE)
        assertEquals("Your subnet is always scanned; one wider than the Large networks limit gets your /24 only. Turn on to scan as much of it as the limit allows, plus other private ranges your phone can reach.", ExtendedCopy.SUPPORT)
        assertEquals("Smart discovery", ExtendedCopy.PROBE_LABEL)
        assertEquals("Looks for gateways at the usual addresses on nearby ranges and sweeps only the ranges that answer. Up to about 1,850 connection attempts, about 30 s.", ExtendedCopy.PROBE_SUPPORT)
        assertEquals("Swept first on every scan, with Aggressive scan on or off", ExtendedCopy.MANUAL_LINE)
        val removed = ExtendedCopy::class.java.declaredFields.map { it.name }
        assertFalse("MANUAL_ON" in removed); assertFalse("MANUAL_OFF" in removed)   // spec 7.2: removed from the draft
    }
}
