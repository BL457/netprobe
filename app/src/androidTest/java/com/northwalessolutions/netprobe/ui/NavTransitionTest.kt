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

/** Fake slot screens, so no test runs a real discovery (spec 1.9). */
@RunWith(AndroidJUnit4::class)
class NavTransitionTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nav: NavHostController
    private fun show(reduced: Boolean) = rule.setContent { NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides reduced) {
        nav = rememberNavController()
        NetProbeNavHost(nav, home = { Box(Modifier.fillMaxSize().testTag("fakeHome")) }, detail = { Box(Modifier.fillMaxSize().testTag("fakeDetail")) },
            settings = { Box(Modifier.fillMaxSize().testTag("fakeSettings")) })
    } } }

    /**
     * Steps the paused clock a frame at a time until [tag] has left the tree, failing past [budgetMs] from the navigation call.
     * NavHost removes the leaving entry only after its transition reports complete, which takes a few frames after the last
     * animation frame (the transition starts from a LaunchedEffect, then completion is posted and recomposed). Asserting at a
     * fixed instant just past NAV_MS failed on the device's frame clock (QA F3), so this waits for the removal with a bound.
     */
    private fun advanceUntilGone(tag: String, startedMs: Long, budgetMs: Long): Long {
        var elapsed = startedMs
        while (rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) {
            assertTrue("$tag still composed ${elapsed} ms after navigating (budget $budgetMs ms)", elapsed <= budgetMs)
            rule.mainClock.advanceTimeByFrame(); elapsed += FRAME_MS
        }
        return elapsed
    }

    @Test fun homeToDetailSlidesAndFadesBothWays() {
        show(reduced = false); rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { nav.navigate("host/1") }
        rule.mainClock.advanceTimeBy(MID_MS)
        rule.onNodeWithTag("fakeHome").assertExists(); rule.onNodeWithTag("fakeDetail").assertExists()   // mid-transition: both
        advanceUntilGone("fakeHome", MID_MS, NAV_DONE_MS)
        rule.onNodeWithTag("fakeHome").assertDoesNotExist(); rule.onNodeWithTag("fakeDetail").assertExists()
        rule.runOnUiThread { nav.popBackStack() }
        rule.mainClock.advanceTimeBy(MID_MS)
        rule.onNodeWithTag("fakeHome").assertExists(); rule.onNodeWithTag("fakeDetail").assertExists()
        advanceUntilGone("fakeDetail", MID_MS, NAV_DONE_MS)
        rule.onNodeWithTag("fakeDetail").assertDoesNotExist(); rule.onNodeWithTag("fakeHome").assertExists()
    }
    @Test fun reducedMotionShowsOnlyTheDestination() {
        show(reduced = true); rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { nav.navigate("settings") }
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("fakeSettings").assertExists()
        // No animation runs: Home leaves within the few frames NavHost needs to complete a zero-length transition, well inside
        // half the 240 ms slide it would otherwise take.
        advanceUntilGone("fakeHome", 2 * FRAME_MS, REDUCED_DONE_MS)
        rule.onNodeWithTag("fakeHome").assertDoesNotExist(); rule.onNodeWithTag("fakeSettings").assertExists()
    }

    private companion object {
        const val FRAME_MS = 16L                                          // the test clock's frame
        const val MID_MS = 120L                                           // half the transition
        const val NAV_DONE_MS = Motion.NAV_MS + 8 * FRAME_MS              // the slide plus completion frames
        const val REDUCED_DONE_MS = Motion.NAV_MS / 2L
    }
}
