package com.northwalessolutions.netprobe.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.ui.detail.DetailActions
import com.northwalessolutions.netprobe.ui.detail.DetailContent
import com.northwalessolutions.netprobe.ui.detail.DetailFakes
import com.northwalessolutions.netprobe.ui.home.*
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlsMotionTest {
    @get:Rule val rule = createComposeRule()
    private val phone = DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 820.dp))
    private val channels = listOf<(Color) -> Float>({ it.red }, { it.green }, { it.blue })

    private fun motion(reduced: Boolean = false, content: @Composable () -> Unit) = rule.setContent {
        NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides reduced) { DeviceConfigurationOverride(phone) { content() } } }
    }
    @Composable private fun home(st: HomeUiState) = HomeContent(st, RecordingHomeActions().actions, now = 121_000L, ownModel = "Pixel 7 Pro")
    /** A pixel on the vertical centre line of [tag], [x] from its left edge (the centre when null). */
    private fun pixel(tag: String, x: Dp? = null): Color {
        val img = rule.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        val px = x?.let { with(rule.density) { it.roundToPx() } } ?: (img.width / 2)
        return img[px, img.height / 2]
    }
    private fun assertBetween(a: Color, b: Color, c: Color, what: String) {
        var differing = 0
        for (ch in channels) {
            val lo = minOf(ch(a), ch(b)); val hi = maxOf(ch(a), ch(b))
            if (hi - lo < CHANNEL_GAP) continue
            differing++
            assertTrue("$what: ${ch(c)} not strictly between $lo and $hi", ch(c) > lo + EPS && ch(c) < hi - EPS)
        }
        assertTrue("$what: the two ends are the same colour", differing > 0)
    }
    private fun assertClose(expected: Color, actual: Color, what: String) { for (ch in channels) assertEquals(what, ch(expected), ch(actual), CLOSE) }

    @Test fun aSelectedChipCrossFadesItsFill() {   // test 9
        rule.mainClock.autoAdvance = false
        var st by mutableStateOf(HomeFakes.settled())
        motion { home(st) }
        rule.mainClock.advanceTimeBy(100)
        assertClose(NetProbeColors.background, pixel("chip:Interesting", FILL_X), "chip before")
        st = st.copy(filter = StatusFilter.INTERESTING); rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(80); assertBetween(NetProbeColors.background, NetProbeColors.surface, pixel("chip:Interesting", FILL_X), "chip at 80 ms")
        rule.mainClock.advanceTimeBy(96); assertClose(NetProbeColors.surface, pixel("chip:Interesting", FILL_X), "chip at 160 ms")
    }
    @Test fun statusAndProfileSegmentsCrossFade() {   // test 9
        rule.mainClock.autoAdvance = false
        var st by mutableStateOf(DetailFakes.state())
        motion { DetailContent(st, DetailActions()) }
        rule.mainClock.advanceTimeBy(100)
        st = st.copy(host = DetailFakes.host(status = HostStatus.INTERESTING), profile = ScanProfile.Full); rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(80)
        assertBetween(NetProbeColors.background, NetProbeColors.mark, pixel("segment:Interesting", FILL_X), "status segment at 80 ms")
        assertBetween(NetProbeColors.background, NetProbeColors.surface, pixel("segment:Full", FILL_X), "profile segment at 80 ms")
        rule.mainClock.advanceTimeBy(96)
        assertClose(NetProbeColors.mark, pixel("segment:Interesting", FILL_X), "status segment at 160 ms")
        assertClose(NetProbeColors.surface, pixel("segment:Full", FILL_X), "profile segment at 160 ms")
    }
    @Test fun reducedMotionSelectionIsInstant() {   // test 9
        rule.mainClock.autoAdvance = false
        var st by mutableStateOf(HomeFakes.settled())
        motion(reduced = true) { home(st) }
        rule.mainClock.advanceTimeBy(100)
        st = st.copy(filter = StatusFilter.INTERESTING); rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        assertClose(NetProbeColors.surface, pixel("chip:Interesting", FILL_X), "reduced motion chip")
    }
    @Test fun scanPillMorphsWithoutMovingTheProfileControl() {   // test 10
        rule.mainClock.autoAdvance = false
        var st by mutableStateOf(DetailFakes.state())
        motion { DetailContent(st, DetailActions()) }
        rule.mainClock.advanceTimeBy(100)
        val nodeId = rule.onNodeWithTag("detailScanButton").fetchSemanticsNode().id
        fun profile() = rule.onNodeWithTag("profileSegments").getBoundsInRoot()
        val at0 = profile()
        val pillWidth = rule.onNodeWithTag("detailScanButton").getBoundsInRoot().width
        fun checkPillBounds(at: String) {   // the morph never shrinks the touch target below 48 dp or changes its width
            val bounds = rule.onNodeWithTag("detailScanButton").getBoundsInRoot()
            assertTrue("pill height ${bounds.height} at $at", bounds.height >= TOUCH_FLOOR)
            assertEquals("pill width at $at", pillWidth, bounds.width)
        }
        st = st.copy(scanning = true); rule.mainClock.advanceTimeByFrame(); checkPillBounds("start")
        var elapsed = 0
        while (elapsed < 90) { rule.mainClock.advanceTimeBy(FRAME_MS); elapsed += FRAME_MS.toInt(); checkPillBounds("$elapsed ms") }
        assertEquals("moved at 90 ms", at0, profile())
        while (elapsed < 110) { rule.mainClock.advanceTimeBy(FRAME_MS); elapsed += FRAME_MS.toInt(); checkPillBounds("$elapsed ms") }
        // Sampled in the fill at FILL_X: the centre pixel sits on the label's glyphs, which cross-fade, so it never measured the fill.
        assertBetween(NetProbeColors.signal, NetProbeColors.background, pixel("detailScanButton", FILL_X), "pill fill at ~110 ms")
        while (elapsed < 220) { rule.mainClock.advanceTimeBy(FRAME_MS); elapsed += FRAME_MS.toInt(); checkPillBounds("$elapsed ms") }
        assertEquals("moved at 220 ms", at0, profile())
        assertEquals("the pill is one node throughout", nodeId, rule.onNodeWithTag("detailScanButton").fetchSemanticsNode().id)
    }

    private companion object {
        /**
         * The 48 dp touch floor as this test can see it (QA F3). ForcedSize(412 x 820 dp) does not fit the Pixel 7 Pro's window,
         * so the override lays the screen out at density 3.41875 (measured on the device), where 48 dp is 164.1 px and
         * heightIn(min = 48.dp) rounds to 164 px, read back as 47.97 dp. In the app, at the phone's own 3.5, it is exactly 168 px.
         * The tenth of a dp allows for that sub-pixel rounding only; a real loss of the floor is a whole pixel or more.
         */
        val TOUCH_FLOOR = 47.9.dp
        val FILL_X = 8.dp                 // inside the fill, clear of the 1 dp outline and the centred label
        const val CHANNEL_GAP = 0.02f
        const val EPS = 0.004f
        const val CLOSE = 0.02f
        const val FRAME_MS = 10L
    }
}
