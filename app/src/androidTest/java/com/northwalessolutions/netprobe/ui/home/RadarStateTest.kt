package com.northwalessolutions.netprobe.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadarStateTest {
    @get:Rule val rule = createComposeRule()

    /** The summary is a pure function of the view-model state passed in; that it survives a Detail round trip is checked on the device (Task 9, a05). */
    @Test fun doneSummaryFollowsTheViewModelStateAndClearsWhenANewScanStarts() {
        val restore = StateRestorationTester(rule)
        var state by mutableStateOf(HomeFakes.settled().copy(scanStartedAt = 1_000L, scanFinishedAt = 13_000L))
        restore.setContent { NetProbeTheme { HomeContent(state, HomeActions(), now = 20_000L, ownModel = "Pixel 7 Pro") } }
        rule.onNodeWithText("Scanned in 12 s").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()   // recomposes from the same state: no local copy is needed or kept
        rule.onNodeWithText("Scanned in 12 s").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        state = state.copy(scanning = true, scanStartedAt = 30_000L, scanFinishedAt = null, status = DiscoveryStatus(total = 254))
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Scanned in 12 s").assertDoesNotExist()
    }
    @Test fun aFreshStateWithoutAFinishedRunShowsTheIdleText() {   // process restart: the view model holds no finish time
        rule.setContent { NetProbeTheme { HomeContent(HomeFakes.settled(), HomeActions(), now = 121_000L, ownModel = "Pixel 7 Pro") } }
        rule.onNodeWithText("Last scan 2 minutes ago").assertIsDisplayed()
    }
}
