package com.northwalessolutions.netprobe.ui.scans

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.LocalBarInset
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3D spec 9.2 item 4: fake state only, no container, no database. Reduced motion keeps the indeterminate lines still. */
@RunWith(AndroidJUnit4::class)
class ScansContentTest {
    @get:Rule val rule = createComposeRule()
    private val events = mutableListOf<String>()
    private val actions = ScansActions(onStop = { events += "stop ${it.key}" }, onOpen = { events += "open ${it.key}" }, onClear = { events += "clear" },
        onUndo = { events += "undo" })
    private val phone = DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 820.dp))

    private fun running(n: Int) = ScansItem("live:$n", ScansSource.PORT_SCAN, n.toLong(), 1, n.toLong(), "Office NAS", null, null, ScansState.RUNNING, 10, 100, 2, 0, 1_000, null, null, null,
        "Port scan on NAS $n", "312 of 65535, found 4")
    private fun recent(n: Int) = ScansItem("live:${100 + n}", ScansSource.PORT_SCAN, null, 1, n.toLong(), "NAS $n", null, null, ScansState.DONE, null, null, null, 0, 14_000, 1, "Done in 14 s, 5 open ports",
        null, "Port scan on NAS $n", "Done in 14 s, 5 open ports, 2 minutes ago")
    private fun show(state: ScansUiState, fontScale: Float = 1f, bar: Boolean = false) = rule.setContent {
        NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides true, LocalBarInset provides (if (bar) WindowInsets(bottom = 80.dp) else null)) {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale) then phone) { ScansContent(state, actions) }
        } }
    }

    @Test fun theRunningBlockIsPinnedWhileTheRecentListScrolls() {
        show(ScansUiState(ScansList(running = (1..3).map(::running), recent = (1..40).map(::recent))))
        val before = rule.onNodeWithTag("running:live:2").getBoundsInRoot()
        rule.onNodeWithTag("scansRecent").performScrollToIndex(40)
        assertEquals(before, rule.onNodeWithTag("running:live:2").getBoundsInRoot())
        val runningNode = rule.onNodeWithTag("running:live:1").fetchSemanticsNode()
        assertFalse("no running row lives inside the scrolling list",
            generateSequence(runningNode.parent) { it.parent }.any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) })
    }
    @Test fun eachRunningRowHasAStopWithItsDescriptionAndTheStopFires() {
        show(ScansUiState(ScansList(running = listOf(running(1))), visibleHostIds = setOf(1L)))
        rule.onNodeWithTag("stop:live:1").assert(hasContentDescription("Stop port scan on NAS 1")).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("stop live:1"), events)
        rule.onNodeWithText("Port scan on NAS 1").performClick()
        assertEquals("stop live:1", events.first()); assertEquals("open live:1", events.last())
    }
    @Test fun clearIsDisabledWithNoRecentItemsAndEnabledWithSome() {
        show(ScansUiState(ScansList(running = listOf(running(1)))))
        rule.onNodeWithTag("scansClear").assertIsNotEnabled()
    }
    @Test fun clearFiresWhenThereIsSomethingToClear() {
        show(ScansUiState(ScansList(recent = listOf(recent(1)))))
        rule.onNodeWithTag("scansClear").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("clear"), events)
    }
    @Test fun theEmptyStateSaysNothingIsRunning() {
        show(ScansUiState())
        rule.onNodeWithText("Nothing running").assertIsDisplayed()
        rule.onNodeWithText("Scans and tool runs show here while they run, and finished ones stay for a day").assertIsDisplayed()
        rule.onNodeWithTag("scansRunning").assertDoesNotExist()
    }
    @Test fun aPendingUndoShowsInTheSnackbarWithAnUndoAction() {
        show(ScansUiState(ScansList(recent = listOf(recent(1))), undoText = "Removed DiskStation"))
        rule.onNodeWithText("Removed DiskStation").assertIsDisplayed()
        rule.onNodeWithText("Undo").assertIsDisplayed().performClick()
        assertEquals(listOf("undo"), events)
    }
    @Test fun atFontScale13WithEightRunningRowsAtLeastOneRecentRowIsStillVisible() {
        show(ScansUiState(ScansList(running = (1..8).map(::running), recent = (1..5).map(::recent))), fontScale = 1.3f, bar = true)
        rule.onNodeWithTag("recent:live:101").assertIsDisplayed()
        rule.onNodeWithTag("stop:live:8").assertIsDisplayed()
    }
}
