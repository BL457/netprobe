package com.northwalessolutions.netprobe.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {
    private val now = 1_000_000_000L
    private fun ago(ms: Long) = RelativeTime.of(now - ms, now) { "date:$it" }
    private val s = 1_000L; private val m = 60 * s; private val h = 60 * m; private val d = 24 * h

    @Test fun `seconds and just now`() {
        assertEquals("just now", ago(0)); assertEquals("just now", ago(4 * s))
        assertEquals("5 seconds ago", ago(5 * s)); assertEquals("59 seconds ago", ago(59 * s))
    }
    @Test fun `minute boundaries`() {
        assertEquals("1 minute ago", ago(60 * s)); assertEquals("2 minutes ago", ago(2 * m)); assertEquals("59 minutes ago", ago(59 * m))
    }
    @Test fun `hour boundaries`() {
        assertEquals("1 hour ago", ago(60 * m)); assertEquals("23 hours ago", ago(23 * h))
    }
    @Test fun `yesterday then the date`() {
        assertEquals("yesterday", ago(24 * h)); assertEquals("yesterday", ago(47 * h))
        assertEquals("date:${now - 2 * d}", ago(2 * d))
    }
    @Test fun `a time in the future reads as just now`() { assertEquals("just now", RelativeTime.of(now + 5 * s, now) { "x" }) }
}
