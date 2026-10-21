package com.northwalessolutions.netprobe.data

import org.junit.Assert.*
import org.junit.Test

/** Final review S3 and S4c: what a heard write may change in a stored row. */
class HeardMergeTest {
    @Test fun `a heard MAC never replaces a stored one, even of equal or lower rank`() {
        assertFalse(HeardMerge.takeMac("aa:bb:cc:dd:ee:01", 1, "aa:bb:cc:dd:ee:02", 2, heard = true))
        assertFalse(HeardMerge.takeMac("aa:bb:cc:dd:ee:01", 1, "aa:bb:cc:dd:ee:02", 1, heard = true))
        assertTrue("a row with no MAC takes the heard one", HeardMerge.takeMac(null, 0, "aa:bb:cc:dd:ee:02", 1, heard = true))
    }
    @Test fun `a scan keeps its rank rule`() {
        assertTrue(HeardMerge.takeMac("aa:bb:cc:dd:ee:01", 1, "aa:bb:cc:dd:ee:02", 1, heard = false))
        assertFalse(HeardMerge.takeMac("aa:bb:cc:dd:ee:01", 3, "aa:bb:cc:dd:ee:02", 1, heard = false))
        assertFalse(HeardMerge.takeMac(null, 0, null, 0, heard = false))
    }
    @Test fun `a heard write always uses the not-weaker identity write, a scan only for the same device`() {
        assertTrue(HeardMerge.keepsStrongerIdentity(true, "aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02"))
        assertFalse(HeardMerge.keepsStrongerIdentity(false, "aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02"))
        assertTrue(HeardMerge.keepsStrongerIdentity(false, "aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:01"))
    }
    @Test fun `a heard union stops at 16 service types but still updates a known one`() {
        val existing = (1..HEARD_TXT_TYPES_MAX).associate { "_t$it._tcp" to mapOf("k" to "v") }
        val out = HeardMerge.txtUnion(existing, mapOf("_extra._tcp" to mapOf("k" to "v"), "_t1._tcp" to mapOf("k" to "new")), heard = true)
        assertEquals(HEARD_TXT_TYPES_MAX, out.size); assertFalse("_extra._tcp" in out); assertEquals("new", out.getValue("_t1._tcp")["k"])
    }
    @Test fun `a heard union that would pass 2 KB keeps the stored TXT`() {
        val existing = mapOf("_a._tcp" to mapOf("k" to "v"))
        val out = HeardMerge.txtUnion(existing, mapOf("_big._tcp" to mapOf("k" to "x".repeat(HEARD_TXT_BYTES_MAX))), heard = true)
        assertEquals(existing, out)
    }
    @Test fun `a scan union is not capped`() {
        val existing = (1..HEARD_TXT_TYPES_MAX).associate { "_t$it._tcp" to mapOf("k" to "v") }
        assertEquals(HEARD_TXT_TYPES_MAX + 1, HeardMerge.txtUnion(existing, mapOf("_extra._tcp" to mapOf("k" to "v")), heard = false).size)
    }
}
