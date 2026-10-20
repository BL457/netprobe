package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.ui.theme.Motion
import org.junit.Assert.*
import org.junit.Test

/** spec 9.1 tests 5 and 28, and every Phase 3A token pinned to its spec 1.2 value. */
class MotionRulesTest {
    @Test fun `rows landing together start 40 ms apart, capped at 200`() {
        val d = RowStagger.delays((1L..8L).toList())
        assertEquals(listOf(0, 40, 80, 120, 160, 200, 200, 200), (1L..8L).map { d.getValue(it) })
        assertEquals(emptyMap<Long, Int>(), RowStagger.delays(emptyList()))
    }
    @Test fun `pulse gate is by time`() {
        assertTrue(PulseGate.shouldStart(1_000, null))
        assertFalse(PulseGate.shouldStart(1_349, 1_000)); assertTrue(PulseGate.shouldStart(1_350, 1_000))
    }
    @Test fun `tokens match spec 1 2`() {
        assertEquals(3000, Motion.ARM_TURN_MS); assertEquals(0.55f, Motion.ARM_HEAD_ALPHA); assertEquals(0.85f, Motion.HEAD_LINE_ALPHA)
        assertEquals(1.5f, Motion.HEAD_LINE_WIDTH.value); assertEquals(120f, Motion.AFTERGLOW_DEG); assertEquals(0.45f, Motion.BLIP_REST_ALPHA)
        assertEquals(1f, Motion.BLIP_STATIC_ALPHA); assertEquals(0.15f, Motion.GLOW_REST_ALPHA); assertEquals(0.45f, Motion.GLOW_LIT_ALPHA)
        assertEquals(1.5f, Motion.GLOW_LIT_SCALE); assertEquals(700, Motion.PULSE_MS); assertEquals(0.35f, Motion.PULSE_ALPHA)
        assertEquals(1.5f, Motion.PULSE_STROKE.value); assertEquals(350L, Motion.PULSE_MIN_GAP_MS); assertEquals(0.10f, Motion.BREATH_MIN)
        assertEquals(0.20f, Motion.BREATH_MAX); assertEquals(360, Motion.SIZE_MS); assertEquals(0.7f, Motion.SETTLE_DAMPING); assertEquals(400f, Motion.SETTLE_STIFFNESS)
        assertEquals(200, Motion.ARM_FADE_MS); assertEquals(160, Motion.LABEL_FADE_MS); assertEquals(300, Motion.BLIP_IN_MS); assertEquals(600, Motion.ROW_ARRIVAL_MS)
        assertEquals(40, Motion.ROW_STAGGER_MS); assertEquals(200, Motion.ROW_STAGGER_MAX_MS); assertEquals(240, Motion.NAV_MS)
        assertEquals(0.25f, Motion.NAV_ENTER_OFFSET); assertEquals(0.10f, Motion.NAV_EXIT_OFFSET); assertEquals(240, Motion.PORT_ROW_MS)
        assertEquals(160, Motion.SELECT_MS); assertEquals(220, Motion.PILL_MORPH_MS); assertEquals(0.88f, Motion.PILL_SQUASH); assertEquals(90, Motion.PILL_SQUASH_AT_MS)
        assertEquals(200, Motion.NOTICE_IN_MS); assertEquals(160, Motion.NOTICE_OUT_MS); assertEquals(240, Motion.IDENTITY_FADE_MS)
        assertEquals(300, Motion.EDGE_COLOUR_MS); assertEquals(250, Motion.PROGRESS_MS); assertEquals(40f, Motion.STATIC_ARM_DEG)
        assertEquals(1200, Motion.QUICK_ARC_TURN_MS)
    }
}
