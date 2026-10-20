package com.northwalessolutions.netprobe.netenv

import org.junit.Assert.*
import org.junit.Test

class CidrParserTest {
    private val local = CidrParser.subnetOf("10.0.3.0/24")!!
    private fun error(s: String) = CidrParser.parseOne(s, local).exceptionOrNull()?.message

    @Test fun `valid forms with whitespace, commas and newlines`() {
        assertEquals(listOf("10.0.4.0/24"), CidrParser.parse("10.0.4.0/24", local).subnets.map { it.toString() })
        assertEquals(listOf("10.0.4.0/24"), CidrParser.parse("  10.0.4.0 / 24 ", local).subnets.map { it.toString() })
        val many = CidrParser.parse("10.0.4.0/24, 10.0.5.0/24\n192.168.50.0/24\r\n", local)
        assertNull(many.error); assertEquals(listOf("10.0.4.0/24", "10.0.5.0/24", "192.168.50.0/24"), many.subnets.map { it.toString() })
    }
    @Test fun `host bits are normalised with a note`() {
        val e = CidrParser.parseOne("10.0.4.20/24", local).getOrThrow()
        assertEquals("10.0.4.0/24", e.subnet.toString()); assertEquals("Normalised to 10.0.4.0/24", e.note)
        assertNull(CidrParser.parseOne("10.0.4.0/24", local).getOrThrow().note)
    }
    @Test fun `every rejection message`() {
        assertEquals(CidrParser.ERR_FORMAT, error("10.0.4.256/24")); assertEquals(CidrParser.ERR_FORMAT, error("10.0.x.0/24"))
        assertEquals(CidrParser.ERR_FORMAT, error("nas.local/24")); assertEquals(CidrParser.ERR_FORMAT, error("fd00::/64"))
        assertEquals(CidrParser.ERR_FORMAT, error("10.0.4.0/24/1"))
        assertEquals(CidrParser.ERR_PREFIX, error("10.0.4.0/2a")); assertEquals(CidrParser.ERR_PREFIX, error("10.0.4.0/33")); assertEquals(CidrParser.ERR_PREFIX, error("10.0.4.0/"))
        assertEquals("Add a prefix, for example /24", error("10.0.4.0"))
        assertEquals("Too large, use /20 or smaller", error("10.0.0.0/19")); assertEquals("Too large, use /20 or smaller", error("10.0.0.0/8"))
        assertEquals("Too small to sweep, use /30 or larger", error("10.0.4.1/31")); assertEquals("Too small to sweep, use /30 or larger", error("10.0.4.1/32"))
        assertEquals("Only private ranges are allowed", error("8.8.8.0/24")); assertEquals("Only private ranges are allowed", error("169.254.1.0/24"))
        assertEquals("Only private ranges are allowed", error("100.64.0.0/24"))
        assertEquals("This is your own network", error("10.0.3.0/24")); assertEquals("This is your own network", error("10.0.3.16/28"))
    }
    @Test fun `a list stops at its first error`() {
        val r = CidrParser.parse("10.0.4.0/24, 8.8.8.0/24, 10.0.5.0/24", local)
        assertEquals("Only private ranges are allowed", r.error); assertEquals(listOf("10.0.4.0/24"), r.subnets.map { it.toString() })
    }
    @Test fun `expansion of slash 20 to 23 and narrower kept as given`() {
        assertEquals(4, CidrParser.units(CidrParser.subnetOf("10.0.4.0/22")!!).size)
        assertEquals(16, CidrParser.units(CidrParser.subnetOf("10.0.16.0/20")!!).size)
        assertEquals(listOf("10.0.4.16/28"), CidrParser.units(CidrParser.subnetOf("10.0.4.16/28")!!).map { it.toString() })
        assertEquals(listOf("10.0.4.0/30"), CidrParser.units(CidrParser.subnetOf("10.0.4.0/30")!!).map { it.toString() })
    }
    @Test fun `exact repeats collapse with a note`() {
        val a = CidrParser.subnetOf("10.0.4.0/24")!!; val b = CidrParser.subnetOf("10.0.5.0/24")!!
        val (list, notes) = CidrParser.merge(listOf(a), listOf(a, b, b))
        assertEquals(listOf(a, b), list); assertEquals(listOf("Duplicate removed: 10.0.4.0/24", "Duplicate removed: 10.0.5.0/24"), notes)
    }
}
