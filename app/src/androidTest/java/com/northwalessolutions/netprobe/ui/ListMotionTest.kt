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
class ListMotionTest {
    @get:Rule val rule = createComposeRule()
    private val phone = DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 820.dp))
    private val channels = listOf<(Color) -> Float>({ it.red }, { it.green }, { it.blue })

    private fun motion(reduced: Boolean = false, content: @Composable () -> Unit) = rule.setContent {
        NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides reduced) { DeviceConfigurationOverride(phone) { content() } } }
    }
    @Composable private fun home(st: HomeUiState) = HomeContent(st, RecordingHomeActions().actions, now = 121_000L, ownModel = "Pixel 7 Pro")
    private fun delayOf(matcher: SemanticsMatcher): Int =
        rule.onNode(matcher and SemanticsMatcher.keyIsDefined(ArrivalDelayKey), useUnmergedTree = true).fetchSemanticsNode().config[ArrivalDelayKey]
    private fun rowDelays(): List<Int> =
        rule.onAllNodes(hasTestTag("hostRow") and SemanticsMatcher.keyIsDefined(ArrivalDelayKey), useUnmergedTree = true).fetchSemanticsNodes().map { it.config[ArrivalDelayKey] }
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

    @Test fun homeRowsLandingInOneEmissionAreStaggered() {   // test 6
        rule.mainClock.autoAdvance = false
        var st by mutableStateOf(HomeFakes.scanning(rows = HomeFakes.rows(2)))
        motion { home(st) }
        rule.mainClock.advanceTimeBy(100)                                   // the scan-start baseline is taken
        st = HomeFakes.scanning(rows = HomeFakes.rows(5)); rule.mainClock.advanceTimeByFrame()
        assertEquals(listOf(-1, -1, 0, 40, 80), rowDelays())
        st = HomeFakes.settled(rows = HomeFakes.rows(5)); rule.mainClock.advanceTimeBy(1_000)
        st = HomeFakes.settled(rows = HomeFakes.rows(5), filter = StatusFilter.UNMARKED); rule.mainClock.advanceTimeBy(100)
        assertTrue("a filter change animated: ${rowDelays()}", rowDelays().all { it == -1 })
    }
    @Test fun aNewPortSlidesTheRowsBelowItIntoPlace() {   // test 8
        rule.mainClock.autoAdvance = false
        val p80 = DetailFakes.port(80, "http"); val p8123 = DetailFakes.port(8123, "http"); val p8443 = DetailFakes.port(8443, "https")
        var st by mutableStateOf(DetailFakes.state(listOf(p80, p8443), scanning = true))
        motion { DetailContent(st, DetailActions()) }
        rule.mainClock.advanceTimeBy(100)
        fun top(port: Int) = rule.onNodeWithText("$port").getBoundsInRoot().top
        val before = top(8443)
        st = st.copy(ports = listOf(p80, p8123, p8443)); rule.mainClock.advanceTimeByFrame()
        assertEquals(0, delayOf(hasAnyDescendant(hasText("8123")))); assertEquals(-1, delayOf(hasAnyDescendant(hasText("80"))))
        assertEquals(-1, delayOf(hasAnyDescendant(hasText("8443"))))
        val tops = (1..15).map { rule.mainClock.advanceTimeBy(16); top(8443) }
        assertTrue("8443 did not move down", tops.last() > before + 1.dp)
        assertTrue("8443 jumped in one frame", tops.first() < tops.last() - 1.dp)
        tops.zipWithNext().forEach { (a, b) -> assertTrue("8443 moved back up", b >= a - 0.5.dp) }
        st = st.copy(scanning = false); rule.mainClock.advanceTimeBy(300)
        st = st.copy(ports = st.ports + DetailFakes.port(9000, "http")); rule.mainClock.advanceTimeByFrame()
        assertEquals(-1, delayOf(hasAnyDescendant(hasText("9000"))))
    }
    @Test fun noticeSlidesInAndOutWithItsOldWords() {   // test 11
        rule.mainClock.autoAdvance = false
        var st by mutableStateOf(HomeFakes.settled())
        motion { home(st) }
        rule.mainClock.advanceTimeBy(100)
        st = st.copy(message = "Scan failed: socket closed"); rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(100)
        val mid = rule.onNodeWithTag("noticeArea").getBoundsInRoot().height
        rule.mainClock.advanceTimeBy(150)
        val full = rule.onNodeWithTag("noticeArea").getBoundsInRoot().height
        assertTrue("mid $mid, full $full", mid > 0.dp && mid < full)
        st = st.copy(message = null); rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeBy(80)
        rule.onNodeWithText("Scan failed: socket closed").assertExists()   // the exit shows the old words, not a blank line
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithTag("noticeArea").assertDoesNotExist()
    }
    @Test fun identityLineCrossFades() {   // test 12
        rule.mainClock.autoAdvance = false
        var st by mutableStateOf(DetailFakes.state())
        motion { DetailContent(st, DetailActions()) }
        rule.mainClock.advanceTimeBy(100)
        st = st.copy(host = DetailFakes.host().copy(identModel = "DS920+")); rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(120)
        rule.onNodeWithText("Synology DS220+  NAS").assertExists(); rule.onNodeWithText("Synology DS920+  NAS").assertExists()
        rule.mainClock.advanceTimeBy(140)
        rule.onNodeWithText("Synology DS220+  NAS").assertDoesNotExist(); rule.onNodeWithText("Synology DS920+  NAS").assertExists()
    }


    private companion object {
        val FILL_X = 8.dp                 // inside the fill, clear of the 1 dp outline and the centred label
        const val CHANNEL_GAP = 0.02f
        const val EPS = 0.004f
        const val CLOSE = 0.02f
    }
}
