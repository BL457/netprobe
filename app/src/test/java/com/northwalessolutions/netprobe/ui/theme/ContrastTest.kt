package com.northwalessolutions.netprobe.ui.theme

import com.northwalessolutions.netprobe.engine.identify.DeviceType
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every row of Radar spec 7.1. A token edit that breaks a floor fails the build. */
class ContrastTest {
    private val bg = NetProbeColors.background
    private val surface = NetProbeColors.surface
    private fun atLeast(min: Double, actual: Double, what: String) = assertTrue("$what is $actual, needs $min", actual >= min)

    @Test fun `text tokens meet 4_5 on background and surface`() {
        for ((name, c) in listOf("text" to NetProbeColors.text, "textSecondary" to NetProbeColors.textSecondary, "textMuted" to NetProbeColors.textMuted,
            "signal" to NetProbeColors.signal, "mark" to NetProbeColors.mark, "error" to NetProbeColors.error)) {
            atLeast(4.5, Contrast.ratio(c, bg), "$name on background"); atLeast(4.5, Contrast.ratio(c, surface), "$name on surface")
        }
    }
    @Test fun `background text on teal and yellow fills`() {
        atLeast(4.5, Contrast.ratio(bg, NetProbeColors.signal), "background on signal")
        atLeast(4.5, Contrast.ratio(bg, NetProbeColors.mark), "background on mark")
    }
    @Test fun `type edges meet the non-text 3 to 1 floor except unknown`() {
        DeviceType.entries.filter { it != DeviceType.UNKNOWN }.forEach { t ->
            atLeast(3.0, Contrast.ratio(t.edgeColor(), bg), "$t edge on background")
            atLeast(3.0, Contrast.ratio(t.edgeColor(), surface), "$t edge on surface")
        }
    }
    @Test fun `stale name and stale edge keep their floors`() {
        atLeast(4.5, Contrast.ratio(Contrast.over(NetProbeColors.text, STALE_NAME_ALPHA, bg), bg), "stale name")
        DeviceType.entries.filter { it != DeviceType.UNKNOWN }.forEach { t ->
            atLeast(3.0, Contrast.ratio(Contrast.over(t.edgeColor(), STALE_EDGE_ALPHA, bg), bg), "stale $t edge")
        }
    }
    @Test fun `radar centre text sits on the plain disc fill`() {
        atLeast(4.5, Contrast.ratio(NetProbeColors.text, NetProbeColors.discCentre), "count on disc centre")
        atLeast(4.5, Contrast.ratio(NetProbeColors.textMuted, NetProbeColors.discCentre), "found label on disc centre")
    }
}
