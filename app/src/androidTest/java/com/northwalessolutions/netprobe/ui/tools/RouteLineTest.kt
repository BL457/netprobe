package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3C spec 8.2 item 6 (the route line) on the 3B results sheet, fake state only. */
@RunWith(AndroidJUnit4::class)
class RouteLineTest {
    @get:Rule val rule = createComposeRule()
    private fun show(state: ToolResultsState) = rule.setContent {
        NetProbeTheme { ToolResultsSheet(state, onOlder = {}, onNewer = {}, onCopy = {}, onOpen = {}, onShare = {}, onDismiss = {}) }
    }

    @Test fun aManualRunsRouteSitsUnderTheTitleAboveTheStatus() {
        show(ToolsFakes.results().copy(route = ToolCopy.ROUTE_OTHER))
        val route = rule.onNodeWithTag("routeLine").assertTextEquals("via VPN or other route").fetchSemanticsNode().positionInRoot.y
        val title = rule.onNodeWithText("Directory brute-force  10.0.3.10:5000").fetchSemanticsNode().positionInRoot.y
        val status = rule.onNodeWithText("Done, 2 hours ago").fetchSemanticsNode().positionInRoot.y
        assertTrue("title $title, route $route, status $status", title < route && route < status)
    }
    @Test fun aDeviceScreenRunShowsNoRouteLine() {
        show(ToolsFakes.results())
        rule.onNodeWithTag("routeLine").assertDoesNotExist()
    }
}
