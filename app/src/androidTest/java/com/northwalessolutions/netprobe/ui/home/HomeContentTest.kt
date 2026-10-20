package com.northwalessolutions.netprobe.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.discovery.*
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.ui.theme.EdgeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import com.northwalessolutions.netprobe.ui.theme.STALE_NAME_ALPHA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeContentTest {
    @get:Rule val rule = createComposeRule()
    private val rec = RecordingHomeActions()
    private fun show(state: HomeUiState) = rule.setContent { NetProbeTheme { HomeContent(state, rec.actions, now = 121_000L, ownModel = "Pixel 7 Pro") } }

    private val lastRun = listOf(RangeProgress("10.0.3.0/24", null, RangeState.DONE, 254, 254, 12),
        RangeProgress("10.0.4.0/24", RangeSource.EVIDENCE, RangeState.DONE, 254, 254, 3),
        RangeProgress("10.0.7.0/24", RangeSource.MANUAL, RangeState.DONE, 254, 254, 0))

    @Test fun rangeSummaryExpandsToOneLinePerRange() {   // extended spec 8.2: a swept but empty range is a finding and must not vanish
        show(HomeFakes.settled().copy(lastRunRanges = lastRun))
        rule.onNodeWithText("2 other ranges checked").assertIsDisplayed()
        rule.onNodeWithTag("rangeLines").assertDoesNotExist()
        rule.onNodeWithText("2 other ranges checked").performClick()
        rule.onNodeWithText("10.0.4.0/24  evidence  swept, 3 found").assertIsDisplayed()
        rule.onNodeWithText("10.0.7.0/24  manual  swept, nothing reachable").assertIsDisplayed()
        rule.onNodeWithTag("deviceList").assertIsDisplayed()
    }
    @Test fun rangeSummaryIsAbsentWhileScanningAndWithoutAnExtendedRun() {
        rule.mainClock.autoAdvance = false
        show(HomeFakes.scanning().copy(lastRunRanges = lastRun)); rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("rangeSummary").assertDoesNotExist()
    }
    @Test fun rangeSummaryIsAbsentWhenNoRangeWasSwept() {   // M4: no "0 other ranges checked" after an early Stop; the notice line says "Stopped at"
        show(HomeFakes.settled().copy(lastRunRanges = listOf(lastRun[0], RangeProgress("10.0.4.0/24", RangeSource.EVIDENCE, RangeState.CANCELLED, 12, 254, 0),
            RangeProgress("10.0.7.0/24", RangeSource.MANUAL, RangeState.PENDING, 0, 254, 0))))
        rule.onNodeWithTag("rangeSummary").assertDoesNotExist()
        rule.onNodeWithText("0 other ranges checked").assertDoesNotExist()
    }
    @Test fun rangeSummaryIsAbsentWithoutAnExtendedRun() {
        show(HomeFakes.settled())
        rule.onNodeWithTag("rangeSummary").assertDoesNotExist()
    }

    @Test fun pinnedHeaderStaysWhileTheListScrolls() {   // spec 10.2 test 1, first half
        show(HomeFakes.settled(HomeFakes.rows(40)))
        val radar = rule.onNodeWithTag("radarBlock").getBoundsInRoot(); val ring = rule.onNodeWithTag("radarRing").getBoundsInRoot()
        rule.onNodeWithTag("deviceList").performScrollToIndex(40)
        assertEquals(radar, rule.onNodeWithTag("radarBlock").getBoundsInRoot()); assertEquals(ring, rule.onNodeWithTag("radarRing").getBoundsInRoot())
        val listTop = rule.onNodeWithTag("deviceList").getBoundsInRoot().top
        assertEquals(listTop, rule.onAllNodesWithTag("groupHeader")[0].getBoundsInRoot().top)   // sticky header stays at the top
    }
    @Test fun scanningAtFontScale13LeavesThreeRowsAndStopIsOutsideTheList() {   // spec 10.2 test 1, second half
        rule.mainClock.autoAdvance = false
        rule.setContent { NetProbeTheme { androidx.compose.ui.test.DeviceConfigurationOverride(
            androidx.compose.ui.test.DeviceConfigurationOverride.FontScale(1.3f) then
                androidx.compose.ui.test.DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 820.dp))) {
            HomeContent(HomeFakes.scanning(HomeFakes.rows(20)), rec.actions, now = 8_000L, ownModel = "Pixel 7 Pro")
        } } }
        rule.mainClock.advanceTimeBy(1_000)
        val h = rule.onNodeWithTag("deviceList").getBoundsInRoot().let { it.bottom - it.top }
        assertTrue("list is $h tall", h >= 186.dp)
        rule.onNode(hasTestTag("radarStop") and hasAnyAncestor(hasTestTag("deviceList"))).assertDoesNotExist()
        rule.onNodeWithTag("radarStop").assertIsDisplayed()
    }
    @Test fun heroHidesChipsAndSearchAndShowsThePlaceholder() {   // spec 10.2 test 3, HERO
        show(HomeFakes.hero())
        rule.onNodeWithTag("scanButton").assertIsDisplayed(); rule.onNodeWithTag("radarDisc").assertIsDisplayed()
        rule.onNodeWithTag("filterRow").assertDoesNotExist(); rule.onNodeWithTag("search").assertDoesNotExist()
        rule.onNodeWithText("Nothing scanned yet").assertIsDisplayed(); rule.onNodeWithText("Devices appear here as they are found").assertIsDisplayed()
    }
    @Test fun heroWithoutANetworkSaysSoOnce() {
        show(HomeFakes.hero(env = null))
        rule.onNodeWithTag("scanButton").assertIsNotEnabled()
        rule.onAllNodesWithText("Connect to Wi-Fi or Ethernet to scan").assertCountEquals(1)
        rule.onAllNodesWithText("No network").assertCountEquals(1)   // the network line stays silent in HERO
    }
    @Test fun settledWithNoNetworkSaysSoOnTheNetworkLine() {   // the ring's reason must be visible, not only in its content description
        show(HomeFakes.settled().copy(env = null, networkName = null))
        rule.onAllNodesWithText("No network").assertCountEquals(1)
        rule.onNodeWithTag("radarRing").assertExists()
    }
    @Test fun aFinishedScanThatFoundNothingStaysSettled() {   // spec 3.1: SETTLED includes a finished scan with zero devices; lastScanAt is null because nothing was stored
        show(HomeFakes.hero().copy(scanStartedAt = 1_000L, scanFinishedAt = 13_000L))
        rule.onNodeWithTag("scanButton").assertDoesNotExist()
        rule.onNodeWithTag("radarRing").assertIsDisplayed()
        rule.onNodeWithText("No devices found").assertIsDisplayed(); rule.onNodeWithText("Scanned in 12 s").assertIsDisplayed()
    }
    @Test fun scanningWithAllAndBlankQueryCollapsesChipsAndSearch() {   // spec 10.2 test 3, SCANNING
        rule.mainClock.autoAdvance = false
        show(HomeFakes.scanning()); rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("radarDisc").assertWidthIsEqualTo(172.dp); rule.onNodeWithTag("radarStop").assertIsDisplayed()
        rule.onNodeWithTag("radarRing").assertDoesNotExist()
        rule.onNodeWithTag("filterRow").assertDoesNotExist(); rule.onNodeWithTag("search").assertDoesNotExist()
    }
    @Test fun chipsStayWhileScanningWithAFilter() {   // Review Focus 1
        rule.mainClock.autoAdvance = false
        val marked = HomeFakes.rows(3).map { it.copy(status = HostStatus.INTERESTING) }
        show(HomeFakes.scanning(marked, filter = StatusFilter.INTERESTING)); rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("filterRow").assertIsDisplayed()
        rule.onNodeWithTag("filterRow").onChildren().filterToOne(hasText("Interesting")).assertIsSelected()
    }
    @Test fun searchStaysWhileScanningWithAQuery() {   // Review Focus 1
        rule.mainClock.autoAdvance = false
        show(HomeFakes.scanning(query = "nas")); rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("search").assertIsDisplayed()
    }
    @Test fun settledShowsTheRingAndTheSummary() {   // spec 10.2 test 3, SETTLED
        show(HomeFakes.settled())
        rule.onNodeWithTag("radarRing").assertWidthIsEqualTo(64.dp)
        rule.onNodeWithText("20 devices").assertIsDisplayed(); rule.onNodeWithText("Last scan 2 minutes ago").assertIsDisplayed()
        rule.onNodeWithTag("radarStop").assertDoesNotExist(); rule.onNodeWithTag("filterRow").assertIsDisplayed()
    }
    @Test fun rowAnatomy() {   // spec 10.2 test 6
        val rows = listOf(
            HomeFakes.row(1, "10.0.3.1", name = "Ubiquiti gateway", type = DeviceType.ROUTER, gateway = true, open = 3),
            HomeFakes.row(2, "10.0.3.242", name = "This phone", identity = "Android device", type = DeviceType.PHONE),
            HomeFakes.row(3, "10.0.3.59", name = "CHNAS", status = HostStatus.INTERESTING, open = 9),
            HomeFakes.row(4, "10.0.3.77", name = "Old box", stale = true, mac = "aa:bb:cc:dd:ee:ff", identity = "Unknown device"))
        show(HomeFakes.settled(rows))
        rule.onNode(SemanticsMatcher.expectValue(EdgeColorKey, EdgeColors.router) and hasContentDescription("gateway", substring = true)).assertExists()
        rule.onNode(SemanticsMatcher.expectValue(EdgeColorKey, EdgeColors.phone) and hasContentDescription("This phone", substring = true)).assertExists()
        rule.onNodeWithText("10.0.3.242  Pixel 7 Pro", useUnmergedTree = true).assertIsDisplayed()
        rule.onNode(hasText("Interesting") and hasAnyAncestor(hasTestTag("hostRow")), useUnmergedTree = true).assertIsDisplayed()   // the chip, not the filter
        rule.onNodeWithText("9 open", useUnmergedTree = true).assertIsDisplayed()
        rule.onNode(hasContentDescription("not seen in the last scan", substring = true)).assertExists()
        rule.onAllNodes(hasTestTag("rowData") and SemanticsMatcher.expectValue(TextAlphaKey, 1f), useUnmergedTree = true).assertCountEquals(4)   // data line never dimmed
        rule.onAllNodes(hasTestTag("hostRow") and SemanticsMatcher.expectValue(TextAlphaKey, 1f), useUnmergedTree = true).assertCountEquals(4)   // no row-wide alpha, stale or not
        rule.onNode(hasTestTag("rowName") and SemanticsMatcher.expectValue(TextAlphaKey, STALE_NAME_ALPHA), useUnmergedTree = true).assertExists()
    }
    @Test fun staleDataLineIsDrawnAtFullStrength() {   // the MVP dimmed the whole row; pixels, not a semantics constant, prove the data line is not dimmed
        var st by mutableStateOf(HomeFakes.settled(listOf(HomeFakes.row(1, "10.0.3.50", name = "Old box", identity = "Unknown device"))))
        rule.setContent { NetProbeTheme { HomeContent(st, rec.actions, now = 121_000L, ownModel = "Pixel 7 Pro") } }
        fun brightest(): Float {
            val img = rule.onNode(hasTestTag("rowData"), useUnmergedTree = true).captureToImage().toPixelMap()
            var best = 0f
            for (x in 0 until img.width) for (y in 0 until img.height) best = maxOf(best, img[x, y].luminance())
            return best
        }
        val fresh = brightest()
        st = st.copy(rows = st.rows.map { it.copy(stale = true) }); rule.waitForIdle()
        val stale = brightest()
        assertTrue("fresh $fresh, stale $stale", stale >= fresh - 0.02f)
    }
    @Test fun otherSubnetGetsItsOwnStickyHeader() {
        show(HomeFakes.settled(HomeFakes.rows(3, other = 2)))
        rule.onNodeWithText("Home subnet 10.0.3.0/24").assertIsDisplayed()
        rule.onNodeWithTag("deviceList").performScrollToNode(hasText("Other subnet 10.0.4.0/24"))
        rule.onNodeWithText("Other subnet 10.0.4.0/24").assertIsDisplayed()
    }
    @Test fun scanDismissesTheNoticeFirstAndTheNoticeDismisses() {
        show(HomeFakes.settled(message = "Large network (/16). Scanned your /24 only. Widen in Settings."))
        rule.onNodeWithTag("notice").assertIsDisplayed()
        rule.onNodeWithTag("radarRing").performClick()
        assertEquals(listOf("dismiss", "scan"), rec.events)
        rule.onNodeWithContentDescription("Dismiss notice").performClick(); assertEquals(2, rec.dismissals)
    }
    @Test fun emptyStatesUseTheSpecWording() {
        show(HomeFakes.settled(rows = emptyList(), query = "zzz").copy(totalHosts = 5))
        rule.onNodeWithText("No devices match \"zzz\"").assertIsDisplayed()
    }
    @Test fun tappingARowOpensIt() {
        show(HomeFakes.settled(HomeFakes.rows(3)))
        rule.onAllNodesWithTag("hostRow")[1].performClick()
        assertEquals(listOf(2L), rec.opened)
    }
    @Test fun aStoppedRunSaysStoppedAfter() {
        show(HomeFakes.settled().copy(scanStartedAt = 1_000L, scanFinishedAt = 13_000L, scanCompleted = false))
        rule.onNodeWithText("Stopped after 12 s").assertIsDisplayed()
    }

    @Test fun undoNoticeShowsAndAnEarlierNoticeReturnsAfterIt() {
        var undos = 0
        var st by mutableStateOf(HomeFakes.settled(message = "Stopped at 10.0.4.0/24").copy(undoText = "Removed DiskStation"))
        rule.setContent { NetProbeTheme { HomeContent(st, HomeActions(onUndo = { undos++ }), now = 121_000L, ownModel = "Pixel 7 Pro") } }
        rule.onNodeWithText("Removed DiskStation").assertIsDisplayed(); rule.onNodeWithText("Stopped at 10.0.4.0/24").assertDoesNotExist()
        rule.onNodeWithTag("noticeUndo").assert(hasContentDescription("Undo")).performClick(); assertEquals(1, undos)
        st = st.copy(undoText = null); rule.waitForIdle()
        rule.onNodeWithText("Stopped at 10.0.4.0/24").assertIsDisplayed()
    }
    @Test fun dismissingTheUndoNoticeCommitsItAndKeepsTheEarlierNotice() {
        var commits = 0; var dismissals = 0
        rule.setContent { NetProbeTheme {
            HomeContent(HomeFakes.settled(message = "Stopped at 10.0.4.0/24").copy(undoText = "Removed DiskStation"),
                HomeActions(onDismissUndo = { commits++ }, onDismissMessage = { dismissals++ }), now = 121_000L, ownModel = "Pixel 7 Pro")
        } }
        rule.onNodeWithContentDescription("Dismiss notice").performClick()
        assertEquals(1, commits); assertEquals(0, dismissals)
    }
    @Test fun forgetThisNetworkIsDisabledWhileARadarOrQuickScanRuns() {
        var st by mutableStateOf(HomeFakes.scanning().copy(networkId = 1))
        rule.setContent { NetProbeTheme { HomeContent(st, rec.actions, now = 121_000L, ownModel = "Pixel 7 Pro") } }
        rule.onNodeWithTag("homeMenu").performClick(); rule.onNodeWithText("Forget this network").assertIsNotEnabled()
        st = HomeFakes.settled().copy(networkId = 1, quickScanning = true); rule.waitForIdle()
        rule.onNodeWithText("Forget this network").assertIsNotEnabled()
    }
    @Test fun forgetThisNetworkRunsWhenIdleAndNeedsANetworkRow() {
        var forgets = 0
        var st by mutableStateOf(HomeFakes.settled().copy(networkId = null))
        rule.setContent { NetProbeTheme { HomeContent(st, HomeActions(onForget = { forgets++ }), now = 121_000L, ownModel = "Pixel 7 Pro") } }
        rule.onNodeWithTag("homeMenu").performClick(); rule.onNodeWithText("Forget this network").assertIsNotEnabled()
        st = st.copy(networkId = 1); rule.waitForIdle()
        rule.onNodeWithText("Forget this network").assertIsEnabled().performClick(); assertEquals(1, forgets)
    }
}
