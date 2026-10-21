package com.northwalessolutions.netprobe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.Motion
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3D spec 1.5 and 9.2 item 3: slide inside a destination, cross-fade between destinations, nothing under reduced motion. */
@RunWith(AndroidJUnit4::class)
class NavTransitionTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nav: NavHostController
    private fun box(tag: String): @androidx.compose.runtime.Composable () -> Unit = { Box(Modifier.fillMaxSize().testTag(tag)) }
    private fun show(reduced: Boolean) = rule.setContent { NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides reduced) {
        nav = rememberNavController()
        NetProbeNavHost(nav, NavSlots(devices = box("fakeDevices"), scans = box("fakeScans"), tools = box("fakeTools"), settings = box("fakeSettings"),
            host = { _, _, _ -> Box(Modifier.fillMaxSize().testTag("fakeHost")) }))
    } } }

    /**
     * Steps the paused clock a frame at a time until [tag] has left the tree, failing past [budgetMs] from the navigation call. NavHost
     * removes the leaving entry only after its transition reports complete, which takes a few frames after the last animation frame
     * (QA F3), so this waits for the removal with a bound instead of asserting at a fixed instant.
     */
    private fun advanceUntilGone(tag: String, startedMs: Long, budgetMs: Long): Long {
        var elapsed = startedMs
        while (rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) {
            assertTrue("$tag still composed $elapsed ms after navigating (budget $budgetMs ms)", elapsed <= budgetMs)
            rule.mainClock.advanceTimeByFrame(); elapsed += FRAME_MS
        }
        return elapsed
    }

    @Test fun devicesToHostSlidesAndFadesBothWays() {
        show(reduced = false); rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { nav.navigate(Routes.host(Tab.DEVICES, 1)) }
        rule.mainClock.advanceTimeBy(MID_MS)
        rule.onNodeWithTag("fakeDevices").assertExists(); rule.onNodeWithTag("fakeHost").assertExists()   // mid-transition: both
        advanceUntilGone("fakeDevices", MID_MS, NAV_DONE_MS)
        rule.onNodeWithTag("fakeDevices").assertDoesNotExist(); rule.onNodeWithTag("fakeHost").assertExists()
        rule.runOnUiThread { nav.popBackStack() }
        rule.mainClock.advanceTimeBy(MID_MS)
        rule.onNodeWithTag("fakeDevices").assertExists(); rule.onNodeWithTag("fakeHost").assertExists()
        advanceUntilGone("fakeHost", MID_MS, NAV_DONE_MS)
        rule.onNodeWithTag("fakeHost").assertDoesNotExist(); rule.onNodeWithTag("fakeDevices").assertExists()
    }
    @Test fun aBarSwitchCrossFadesBetweenDestinations() {
        show(reduced = false); rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { TabSwitch.select(nav, Tab.SETTINGS) }
        rule.mainClock.advanceTimeBy(MID_MS)
        rule.onNodeWithTag("fakeDevices").assertExists(); rule.onNodeWithTag("fakeSettings").assertExists()
        advanceUntilGone("fakeDevices", MID_MS, NAV_DONE_MS)
        rule.onNodeWithTag("fakeSettings").assertExists()
    }
    @Test fun reducedMotionShowsOnlyTheDestinationForAPushAndForABarSwitch() {
        show(reduced = true); rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { nav.navigate(Routes.host(Tab.DEVICES, 1)) }
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("fakeHost").assertExists()
        advanceUntilGone("fakeDevices", 2 * FRAME_MS, REDUCED_DONE_MS)
        rule.runOnUiThread { nav.popBackStack() }
        advanceUntilGone("fakeHost", 0, REDUCED_DONE_MS)
        rule.runOnUiThread { TabSwitch.select(nav, Tab.TOOLS) }
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("fakeTools").assertExists()
        advanceUntilGone("fakeDevices", 2 * FRAME_MS, REDUCED_DONE_MS)
    }

    private companion object {
        const val FRAME_MS = 16L                                          // the test clock's frame
        const val MID_MS = 120L                                           // half the transition
        const val NAV_DONE_MS = Motion.NAV_MS + 8 * FRAME_MS              // the animation plus completion frames
        const val REDUCED_DONE_MS = Motion.NAV_MS / 2L
    }
}
