package com.northwalessolutions.netprobe.ui.home

import androidx.compose.ui.geometry.Offset
import com.northwalessolutions.netprobe.ui.theme.Motion
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** spec 9.1 tests 1 to 4, and the reduced-motion blip look of 9.2 test 5 (a draw-phase value no Compose test can read). */
class RadarMotionTest {
    @Test fun `arm trail stops put the bright edge at the head`() {
        val s = ArmTrail.stops()
        assertEquals(listOf(0f, 0.6111f, 0.7222f, 0.8333f, 0.9167f, 0.9722f, 0.9972f, 1f), s.map { (it.first * 10_000).roundToInt() / 10_000f })
        assertEquals(listOf(0f, 0f, 0.03f, 0.09f, 0.20f, 0.38f, 0.55f, 0.55f), s.map { it.second })
        assertEquals(0.55f, s.last().second, 0f); assertEquals(1f, s.last().first, 0f)
        val tailStart = s.single { abs(it.first - 0.6111f) < 0.0001f }
        assertEquals(0f, tailStart.second, 0f); assertEquals(0f, s.first().second, 0f)
        s.filter { it.first >= tailStart.first }.zipWithNext().forEach { (a, b) -> assertTrue("alpha falls from $a to $b", b.second >= a.second) }
        assertEquals(140f, Motion.TRAIL_DEG, 0f); assertEquals(Motion.TRAIL_DEG, (1f - tailStart.first) * 360f, 0.05f)
    }
    @Test fun `afterglow decays over 120 degrees behind the arm`() {
        assertEquals(1f, afterglow(100f, 100f), 1e-6f)
        assertEquals(0.25f, afterglow(160f, 100f), 1e-6f)
        assertEquals(0f, afterglow(220f, 100f), 0f); assertEquals(0f, afterglow(300f, 100f), 0f)
        assertEquals("just ahead of the arm (d = 359)", 0f, afterglow(99f, 100f), 0f)
        val g = 1f - 20f / 120f
        assertEquals("wraps across 0/360", g * g, afterglow(10f, 350f), 1e-6f)
        assertEquals("the resumed arm runs past 360", afterglow(10f, 350f), afterglow(370f, 350f), 1e-6f)
    }
    @Test fun `breath peaks at 0 degrees and dips at 180`() {
        assertEquals(Motion.BREATH_MAX, breath(0f), 1e-6f); assertEquals(Motion.BREATH_MIN, breath(180f), 1e-6f)
        for (d in 0..720 step 7) { val b = breath(d.toFloat()); assertTrue("breath($d) = $b", b >= Motion.BREATH_MIN - 1e-6f && b <= Motion.BREATH_MAX + 1e-6f) }
    }
    @Test fun `blip angles match the arm's clockwise angle`() {
        for (i in 0 until RadarGeometry.MAX_BLIPS) {
            val a = blipAngleDeg(blipOffset(i, 100f))
            assertTrue("blip $i at $a", a >= 0f && a < 360f)
            val expected = ((i * RadarGeometry.GOLDEN_ANGLE_DEG + RadarGeometry.jitterDeg(i)) % 360f + 360f) % 360f
            assertEquals("blip $i", expected, a, 0.01f)
        }
        assertEquals("screen y points down: below the centre is 90 degrees", 90f, blipAngleDeg(Offset(0f, 10f)), 1e-4f)
    }
    @Test fun `blip look at rest, lit and under reduced motion`() {
        assertEquals(Motion.BLIP_REST_ALPHA, blipCoreAlpha(0f, false), 1e-6f); assertEquals(1f, blipCoreAlpha(1f, false), 1e-6f)
        assertEquals(Motion.GLOW_REST_ALPHA, blipGlowAlpha(0f, false), 1e-6f); assertEquals(Motion.GLOW_LIT_ALPHA, blipGlowAlpha(1f, false), 1e-6f)
        assertEquals(1f, blipGlowScale(0f, false), 1e-6f); assertEquals(Motion.GLOW_LIT_SCALE, blipGlowScale(1f, false), 1e-6f)
        assertEquals(Motion.BLIP_STATIC_ALPHA, blipCoreAlpha(1f, true), 0f); assertEquals(Motion.GLOW_REST_ALPHA, blipGlowAlpha(1f, true), 0f)
        assertEquals(1f, blipGlowScale(1f, true), 0f)
        assertEquals("half lit", 0.725f, blipCoreAlpha(0.5f, false), 1e-6f)
        // The radar passes glow 0 under reduced motion: the static look, never the dimmed rest look.
        assertEquals(Motion.BLIP_STATIC_ALPHA, blipCoreAlpha(0f, true), 0f); assertEquals(Motion.GLOW_REST_ALPHA, blipGlowAlpha(0f, true), 0f)
        assertEquals(1f, blipGlowScale(0f, true), 0f)
    }
}
