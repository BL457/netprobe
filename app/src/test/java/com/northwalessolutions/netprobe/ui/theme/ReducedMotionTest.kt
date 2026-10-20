package com.northwalessolutions.netprobe.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReducedMotionTest {
    @Test fun `only an animator scale of zero means reduced`() {
        assertTrue(ReducedMotion.fromScale(0f))
        assertFalse(ReducedMotion.fromScale(0.5f)); assertFalse(ReducedMotion.fromScale(1f)); assertFalse(ReducedMotion.fromScale(10f))
    }
}
