package com.northwalessolutions.netprobe.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadarBlockTest {
    @get:Rule val rule = createComposeRule()
    private val summary = RadarSummary("13 devices", "Last scan 2 minutes ago")

    private fun show(mode: RadarMode, reduced: Boolean = false, canScan: Boolean = true, quick: Boolean = false,
                     onScan: () -> Unit = {}, onStop: () -> Unit = {}, onStopQuick: () -> Unit = {}) = rule.setContent {
        NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides reduced) {
            RadarBlock(mode, found = 9, progressLine = "probing 171 of 254  7 s", summary = summary, canScan = canScan, onScan = onScan,
                onStop = onStop, heroSubnet = "10.0.3.0/24", quickScanning = quick, onStopQuick = onStopQuick)
        } }
    }

    @Test fun discDoesNothingAndOnlyStopStops() {   // spec 10.2 test 4
        rule.mainClock.autoAdvance = false
        var stops = 0; var scans = 0
        show(RadarMode.SCANNING, onStop = { stops++ }, onScan = { scans++ })
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("radarDisc").performClick(); rule.mainClock.advanceTimeByFrame()
        assertEquals(0, stops); assertEquals(0, scans)
        rule.onNodeWithTag("radarStop").performClick(); rule.mainClock.advanceTimeByFrame()
        assertEquals(1, stops)
    }
    @Test fun settledRingStartsAScanAndCarriesTheSummary() {
        var scans = 0
        show(RadarMode.SETTLED, onScan = { scans++ })
        rule.onNodeWithTag("radarRing").assertWidthIsEqualTo(Dimens.ringTarget)
            .assert(hasContentDescription("Scan, 13 devices, last scan 2 minutes ago"))
        rule.onNodeWithText("13 devices").assertIsDisplayed()
        rule.onNodeWithTag("radarStop").assertDoesNotExist(); rule.onNodeWithTag("radarDisc").assertDoesNotExist()
        rule.onNodeWithTag("radarRing").performClick(); assertEquals(1, scans)
    }
    @Test fun scanningShowsTheDiscTheCountAndStop() {
        rule.mainClock.autoAdvance = false
        show(RadarMode.SCANNING); rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("radarDisc").assertWidthIsEqualTo(Dimens.discDiameter)
        rule.onNodeWithTag("radarCount", useUnmergedTree = true).assertTextEquals("9")
        rule.onNodeWithTag("radarStop").assertIsDisplayed(); rule.onNodeWithTag("radarRing").assertDoesNotExist()
        rule.onNodeWithTag("radarProgress").assertTextEquals("probing 171 of 254  7 s")
        rule.onNode(SemanticsMatcher.expectValue(ArmSpinningKey, true), useUnmergedTree = true).assertExists()
    }
    @Test fun heroWithoutANetworkDisablesTheCircleAndSaysSoOnce() {
        show(RadarMode.HERO, canScan = false)
        rule.onNodeWithTag("scanButton").assertIsNotEnabled()
        rule.onAllNodesWithText("Connect to Wi-Fi or Ethernet to scan").assertCountEquals(1)
        rule.onNodeWithText("No network").assertIsDisplayed()
    }
    @Test fun heroWithANetworkOffersTheSubnet() {
        var scans = 0
        show(RadarMode.HERO, onScan = { scans++ })
        rule.onNodeWithText("Nothing scanned yet").assertIsDisplayed(); rule.onNodeWithText("Tap to find what is on 10.0.3.0/24").assertIsDisplayed()
        rule.onNodeWithTag("scanButton").assert(hasContentDescription("Scan the network 10.0.3.0/24")).performClick()
        assertEquals(1, scans)
    }
    @Test fun quickScanRingShowsStopAndStopsTheQuickScan() {
        var stops = 0
        show(RadarMode.SETTLED, quick = true, onStopQuick = { stops++ })
        rule.onNodeWithText("Quick scanning").assertIsDisplayed()
        rule.onNodeWithTag("radarRing").assert(hasContentDescription("Stop quick scan")).performClick()
        assertEquals(1, stops)
    }
    @Test fun reducedMotionGrowsInOneFrameWithAStaticArm() {   // spec 10.2 test 5
        rule.mainClock.autoAdvance = false
        var mode by mutableStateOf(RadarMode.SETTLED)
        rule.setContent { NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides true) {
            RadarBlock(mode, 3, "probing 3 of 254  1 s", summary, true, {}, {})
        } } }
        mode = RadarMode.SCANNING
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("radarDisc").assertWidthIsEqualTo(Dimens.discDiameter)
        rule.onNode(SemanticsMatcher.expectValue(ArmSpinningKey, false), useUnmergedTree = true).assertExists()
    }
    @Test fun normalMotionGrowsOverTheTransition() {
        rule.mainClock.autoAdvance = false
        var mode by mutableStateOf(RadarMode.SETTLED)
        rule.setContent { NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides false) {
            RadarBlock(mode, 3, "probing 3 of 254  1 s", summary, true, {}, {})
        } } }
        mode = RadarMode.SCANNING
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        val early = rule.onNodeWithTag("radarDisc").getBoundsInRoot().width
        assertTrue("disc already full size after two frames: $early", early < Dimens.discDiameter - 1.dp)
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("radarDisc").assertWidthIsEqualTo(Dimens.discDiameter)
    }
    @Test fun blockHeightSlidesWithoutAStepBothWays() {   // fix round 1, I1: pill area height and alpha follow the disc fraction
        rule.mainClock.autoAdvance = false
        var mode by mutableStateOf(RadarMode.SETTLED)
        rule.setContent { NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides false) {
            RadarBlock(mode, 3, "probing 3 of 254  1 s", summary, true, {}, {})
        } } }
        rule.mainClock.advanceTimeByFrame()
        fun height() = rule.onNodeWithTag("radarBlock").getBoundsInRoot().height
        val ring = height()
        assertEquals(Dimens.ringTarget.value, ring.value, 0.5f)
        mode = RadarMode.SCANNING
        rule.mainClock.advanceTimeBy(1_000)
        val full = height()
        assertTrue("full height $full not above the ring row $ring", full > ring + 100.dp)
        mode = RadarMode.SETTLED; rule.mainClock.advanceTimeBy(1_000)
        assertEquals(ring.value, height().value, 0.5f)

        mode = RadarMode.SCANNING
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        val start = height()
        assertTrue("expand stepped: $start after two frames (ring $ring, full $full)", start - ring < 12.dp)
        rule.mainClock.advanceTimeBy(180)
        val mid = height()
        assertTrue("expand midpoint $mid not between $ring and $full", mid > ring + 1.dp && mid < full - 1.dp)
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(full.value, height().value, 0.5f)

        mode = RadarMode.SETTLED
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        val settleStart = height()
        assertTrue("settle stepped: $settleStart after two frames (ring $ring, full $full)", full - settleStart < 12.dp)
        rule.mainClock.advanceTimeBy(180)
        val settleMid = height()
        assertTrue("settle midpoint $settleMid not between $ring and $full", settleMid > ring + 1.dp && settleMid < full - 1.dp)
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(ring.value, height().value, 0.5f)
        rule.onNodeWithTag("radarStop").assertDoesNotExist()
    }
}
