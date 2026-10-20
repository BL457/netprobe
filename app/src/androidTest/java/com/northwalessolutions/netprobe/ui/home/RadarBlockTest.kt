package com.northwalessolutions.netprobe.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsPropertyKey
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
import org.junit.Assert.assertFalse
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
        rule.mainClock.advanceTimeBy(48)
        val settleMid = height()
        assertTrue("settle midpoint $settleMid not between $ring and $full", settleMid > ring + 1.dp && settleMid < full - 1.dp)
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(ring.value, height().value, 0.5f)
        rule.onNodeWithTag("radarStop").assertDoesNotExist()
    }

    private fun hosted(reduced: Boolean = false, content: @Composable () -> Unit) =
        rule.setContent { NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides reduced) { content() } } }
    private fun <T> value(key: SemanticsPropertyKey<T>): T = rule.onNodeWithTag("radarBlock").fetchSemanticsNode().config[key]

    @Test fun sweepRunsAndBreathesWhileScanningAndStopsAfterTheSettle() {
        rule.mainClock.autoAdvance = false
        var mode by mutableStateOf(RadarMode.SETTLED)
        hosted { RadarBlock(mode, 3, "probing 3 of 254  1 s", summary, true, {}, {}) }
        mode = RadarMode.SCANNING; rule.mainClock.advanceTimeBy(16)
        assertTrue(value(ArmSpinningKey)); assertTrue(value(BreathingKey))
        mode = RadarMode.SETTLED; rule.mainClock.advanceTimeBy(1_000)
        assertFalse(value(ArmSpinningKey)); assertFalse(value(BreathingKey))
    }
    @Test fun ringPulsesAreGatedByTimeAndEveryPulseFinishes() {
        rule.mainClock.autoAdvance = false
        var mode by mutableStateOf(RadarMode.SCANNING); var found by mutableIntStateOf(0)
        hosted { RadarBlock(mode, found, null, summary, true, {}, {}) }
        rule.mainClock.advanceTimeBy(500)                                   // the expand is done
        val counts = (1..20).map { i -> found = i; rule.mainClock.advanceTimeBy(50); value(PulseCountKey) }   // counts[k] is at (k + 1) * 50 ms
        // The contract, not the frame (plan review G5): a pulse starts two or three frames after its find, so the spec's
        // 100 / 300 / 400 ms marks are checked with that lag allowed: 1 by 150 ms, still 1 at 300 ms, 2 by 450 ms.
        assertEquals("by 150 ms", 1, counts[2]); assertEquals("at 300 ms", 1, counts[5]); assertEquals("by 450 ms", 2, counts[8])
        for (k in 7 until counts.size) assertTrue("two starts inside one 350 ms window ending at ${(k + 1) * 50} ms", counts[k] - counts[k - 7] <= 1)
        rule.mainClock.advanceTimeBy(700)
        assertFalse("a pulse froze on the disc", value(PulseActiveKey))
        mode = RadarMode.SETTLED; rule.mainClock.advanceTimeBy(1_000)
        found = 0; mode = RadarMode.SCANNING; rule.mainClock.advanceTimeBy(100)   // a new scan resets found: no pulse, count reset
        assertEquals(0, value(PulseCountKey)); assertFalse(value(PulseActiveKey))
    }
    @Test fun springSettleUndershootsOnlyTheRingAndLandsCleanly() {
        rule.mainClock.autoAdvance = false
        var mode by mutableStateOf(RadarMode.SCANNING); var busy = true
        hosted { RadarBlock(mode, 3, null, summary, true, {}, {}, onBusyChange = { busy = it }) }
        rule.mainClock.advanceTimeBy(1_000)
        mode = RadarMode.SETTLED
        val fractions = mutableListOf<Float>()
        for (step in 1..37) {                                               // 16 ms samples for about 600 ms
            rule.mainClock.advanceTimeBy(16)
            val fr = value(RadarFractionKey); fractions += fr
            val h = rule.onNodeWithTag("radarBlock").getBoundsInRoot().height
            assertTrue("block height $h fell below the settled ${Dimens.ringTarget}", h >= Dimens.ringTarget - 0.5.dp)
            if (fr != 0f) rule.onNodeWithTag("radarRing").assertIsNotEnabled()
        }
        assertTrue("no undershoot, minimum ${fractions.min()}", fractions.min() < 0f)
        assertEquals("not at rest by about 500 ms (the 512 ms sample)", 0f, fractions[31], 0f)   // spec: about 350 to 400 ms, plus start lag (G5)
        rule.onNodeWithTag("radarRing").assertIsEnabled()
        assertFalse(busy)
    }
    // Counts RadarBlock and its three layer composables (plan review G6): a per-frame read in a layer's body would show here.
    @Test fun steadyScanningDoesNotRecomposePerFrame() {
        rule.mainClock.autoAdvance = false
        var compositions = 0
        hosted { RadarBlock(RadarMode.SCANNING, 9, "probing 171 of 254  7 s", summary, true, {}, {}, onComposed = { compositions++ }) }
        rule.mainClock.advanceTimeBy(1_000)                                 // after the expand (which recomposes per frame by design)
        val before = compositions
        repeat(60) { rule.mainClock.advanceTimeByFrame() }
        assertTrue("RadarBlock and its layers recomposed ${compositions - before} times in 60 frames", compositions - before <= 2)
    }
    @Test fun reducedMotionRadarIsStaticAndInstant() {
        rule.mainClock.autoAdvance = false
        var mode by mutableStateOf(RadarMode.SCANNING); var found by mutableIntStateOf(1)
        hosted(reduced = true) { RadarBlock(mode, found, null, summary, true, {}, {}) }
        rule.mainClock.advanceTimeByFrame()
        assertFalse(value(ArmSpinningKey)); assertFalse(value(BreathingKey))
        found = 5; rule.mainClock.advanceTimeBy(100)
        assertEquals(0, value(PulseCountKey)); assertFalse(value(PulseActiveKey))
        mode = RadarMode.SETTLED; rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        assertEquals(0f, value(RadarFractionKey), 0f)
    }
}
