package com.northwalessolutions.netprobe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
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

    @Test fun homeToDetailSlidesAndFadesBothWays() {
        show(reduced = false); rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { nav.navigate("host/1") }
        rule.mainClock.advanceTimeBy(120)
        rule.onNodeWithTag("fakeHome").assertExists(); rule.onNodeWithTag("fakeDetail").assertExists()
        rule.mainClock.advanceTimeBy(140)
        rule.onNodeWithTag("fakeHome").assertDoesNotExist(); rule.onNodeWithTag("fakeDetail").assertExists()
        rule.runOnUiThread { nav.popBackStack() }
        rule.mainClock.advanceTimeBy(120)
        rule.onNodeWithTag("fakeHome").assertExists(); rule.onNodeWithTag("fakeDetail").assertExists()
        rule.mainClock.advanceTimeBy(140)
        rule.onNodeWithTag("fakeDetail").assertDoesNotExist(); rule.onNodeWithTag("fakeHome").assertExists()
    }
    @Test fun reducedMotionShowsOnlyTheDestination() {
        show(reduced = true); rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { nav.navigate("settings") }
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("fakeHome").assertDoesNotExist(); rule.onNodeWithTag("fakeSettings").assertExists()
    }
}
