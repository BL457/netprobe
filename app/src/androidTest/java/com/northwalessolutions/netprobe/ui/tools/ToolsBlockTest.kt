package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** spec 12.2 item 1. */
@RunWith(AndroidJUnit4::class)
class ToolsBlockTest {
    @get:Rule val rule = createComposeRule()
    private val ran = mutableListOf<Pair<ToolId, Int>>(); private val opened = mutableListOf<ToolId>()

    private fun show(state: ToolsUiState, busy: Boolean = false) = rule.setContent {
        var expanded by remember { mutableStateOf(false) }
        NetProbeTheme { ToolsBlock(state, expanded, busy, onToggle = { expanded = !expanded }, onRunChip = { t, p -> ran += t to p }, onResults = { opened += it }) }
    }

    @Test fun collapsedByDefaultWithAvailableAndTheNewestHistory() {
        show(ToolsFakes.tools())
        // The header clears its children's semantics and speaks one description, so its texts are not separate nodes.
        rule.onNodeWithTag("toolsHeader").assertIsDisplayed().assert(hasContentDescription("Tools, 2 available, expand"))
        rule.onNodeWithText("Tools").assertDoesNotExist()
        rule.onNodeWithTag("toolChip:dir-bruteforce:5000").assertDoesNotExist()
    }
    @Test fun expandsAndCollapses() {
        show(ToolsFakes.tools())
        rule.onNodeWithTag("toolsHeader").performClick()
        rule.onNodeWithTag("toolsHeader").assert(hasContentDescription("Tools, 2 available, collapse"))
        rule.onNodeWithTag("toolChip:dir-bruteforce:5001").assertIsDisplayed()
        rule.onNodeWithTag("toolsHeader").performClick()
        rule.onNodeWithTag("toolChip:dir-bruteforce:5001").assertDoesNotExist()
    }
    @Test fun chipsFireTheRightLambdaAndResultsShowsOnlyWithARun() {
        show(ToolsFakes.tools())
        rule.onNodeWithTag("toolsHeader").performClick()
        rule.onNodeWithTag("toolChip:http-fingerprint:5000").assert(hasContentDescription("Run HTTP fingerprint on port 5000")).performClick()
        assertEquals(listOf(ToolId.HTTP_FINGERPRINT to 5000), ran)
        rule.onNodeWithTag("toolResults:dir-bruteforce").assert(hasContentDescription("Show Directory brute-force results")).performClick()
        assertEquals(listOf(ToolId.DIR_BRUTEFORCE), opened)
        rule.onNodeWithTag("toolResults:http-fingerprint").assertDoesNotExist()
        rule.onAllNodesWithText("Results").assertCountEquals(1)
    }
    @Test fun chipTargetsAreAtLeast48dp() {
        show(ToolsFakes.tools())
        rule.onNodeWithTag("toolsHeader").performClick()
        rule.onNodeWithTag("toolChip:dir-bruteforce:5000").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
    }
    @Test fun whileHeldAChipStillReportsTheTapSoTheScreenCanSayWhy() {
        show(ToolsFakes.tools(), busy = true)
        rule.onNodeWithTag("toolsHeader").performClick()
        rule.onNodeWithTag("toolChip:dir-bruteforce:5000").performClick()
        assertEquals(listOf(ToolId.DIR_BRUTEFORCE to 5000), ran)   // the view model answers a held host with its busy snackbar (Task 16)
    }
}
