package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** spec 12.2 item 5. */
@RunWith(AndroidJUnit4::class)
class ToolResultsSheetTest {
    @get:Rule val rule = createComposeRule()
    private val events = mutableListOf<String>()
    private fun show(state: ToolResultsState) = rule.setContent {
        NetProbeTheme { ToolResultsSheet(state, onOlder = { events += "older" }, onNewer = { events += "newer" }, onCopy = { events += "copy ${it.id}" },
            onOpen = { events += "open ${it.id}" }, onShare = { events += "share" }, onDismiss = {}) }
    }

    @Test fun openWithOnlyWithAUrlCopyAlwaysAndEveryTargetIs48dp() {
        show(ToolsFakes.results())
        rule.onNodeWithTag("open:1").assert(hasContentDescription("Open http://10.0.3.10:5000/admin with")).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("open:2").assertDoesNotExist()
        rule.onNodeWithTag("copy:2").assert(hasContentDescription("Copy Pi-hole")).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("copy:1").assertHeightIsAtLeast(48.dp)
        assertEquals(listOf("open 1", "copy 2"), events)
    }
    @Test fun longPressOnAFindingCopiesIt() {
        show(ToolsFakes.results())
        rule.onNodeWithTag("finding:1").performTouchInput { longClick() }
        assertEquals(listOf("copy 1"), events)
    }
    @Test fun theFallbackPrefixIsItsOwnLineAboveTheRestOfTheSummary() {
        val s = ToolsFakes.results().copy(summary = ToolCopy.bruteFallback("7 found in 300 paths"))
        show(s)
        rule.onNodeWithTag("fallbackLine").assertIsDisplayed().assertTextEquals(ToolCopy.BRUTE_FALLBACK_PREFIX)
        rule.onNodeWithText("7 found in 300 paths").assertIsDisplayed()
    }
    @Test fun theHeaderShowsTitleStatusAndSummary() {
        show(ToolsFakes.results())
        rule.onNodeWithText("Directory brute-force  10.0.3.10:5000").assertIsDisplayed()
        rule.onNodeWithText("Done, 2 hours ago").assertIsDisplayed()
        rule.onNodeWithText("7 found in 300 paths").assertIsDisplayed()
        rule.onNodeWithTag("fallbackLine").assertDoesNotExist()
    }
    @Test fun theRunSwitcherIsBoundedAtBothEnds() {
        show(ToolsFakes.results(index = 1, count = 3))
        rule.onNodeWithText("Run 1 of 3").assertIsDisplayed()
        rule.onNodeWithTag("newerRun").assertIsNotEnabled(); rule.onNodeWithTag("olderRun").assertIsEnabled().performClick()
        assertEquals(listOf("older"), events)
    }
    @Test fun theOldestRunCannotGoOlder() {
        show(ToolsFakes.results(index = 3, count = 3))
        rule.onNodeWithTag("olderRun").assertIsNotEnabled(); rule.onNodeWithTag("newerRun").assertIsEnabled()
    }
    @Test fun emptyStatesAndShareDisabledWhileRunning() {
        show(ToolsFakes.results(findings = emptyList(), active = true))
        rule.onNodeWithText("Running, findings appear as they are found").assertIsDisplayed()
        rule.onNodeWithTag("shareRun").assertIsNotEnabled()
    }
    @Test fun aFinishedRunWithNothingSaysNoFindingsAndShares() {
        show(ToolsFakes.results(findings = emptyList()))
        rule.onNodeWithText("No findings").assertIsDisplayed()
        rule.onNodeWithTag("shareRun").assertIsEnabled().performClick(); assertEquals(listOf("share"), events)
    }
}
