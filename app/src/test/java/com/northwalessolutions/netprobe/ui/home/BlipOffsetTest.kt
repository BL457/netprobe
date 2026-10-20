package com.northwalessolutions.netprobe.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class BlipOffsetTest {
    private val r = 86f   // the 172 dp disc at 1 px per dp
    @Test fun `deterministic per index`() { for (i in 0 until 64) assertEquals(blipOffset(i, r), blipOffset(i, r)) }
    @Test fun `radius stays within the band and inside the disc`() {
        for (i in 0 until 64) {
            val o = blipOffset(i, r); val d = hypot(o.x, o.y)
            assertTrue("blip $i at $d", d >= RadarGeometry.MIN_RADIUS_FRACTION * r - 0.01f && d <= RadarGeometry.MAX_RADIUS_FRACTION * r + 0.01f && d < r)
        }
    }
    @Test fun `first 64 blips are at least 4 dp apart`() {
        val pts = (0 until 64).map { blipOffset(it, r) }
        for (i in pts.indices) for (j in 0 until i) {
            val d = hypot(pts[i].x - pts[j].x, pts[i].y - pts[j].y)
            assertTrue("blips $i and $j are $d apart", d >= 4f)
        }
    }
    @Test fun `centre mask never outgrows the disc while it grows`() {
        val mask = 40f
        assertEquals(40f, RadarGeometry.armMask(mask, 86f), 0.01f)         // full disc: the 40 dp mask as specified
        assertEquals(26.32f, RadarGeometry.armMask(mask, 56f), 0.01f)      // ring size at the first scanning frame
        for (r in listOf(28f, 40f, 56f, 70f, 86f)) assertTrue("mask ${RadarGeometry.armMask(mask, r)} at radius $r", RadarGeometry.armMask(mask, r) < r)
    }
}
