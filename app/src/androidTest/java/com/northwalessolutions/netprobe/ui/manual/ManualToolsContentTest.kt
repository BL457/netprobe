package com.northwalessolutions.netprobe.ui.manual

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.ui.home.HomeCopy
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolsFakes
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3C spec 8.2 item 6: fake state only, no container, no database. */
@RunWith(AndroidJUnit4::class)
class ManualToolsContentTest {
    @get:Rule val rule = createComposeRule()
    private val events = mutableListOf<String>()
    private var s by mutableStateOf(ManualUiState())
    private val actions = ManualActions(onRun = { events += "run" }, onStop = { events += "stop" }, onOpenRun = { events += "open $it" },
        onTool = { events += "tool ${it.key}" }, onManageWordlists = { events += "manage wordlists" })
    private var live: String? = null
    private fun show(state: ManualUiState) { s = state; rule.setContent { NetProbeTheme { ManualToolsContent(s, actions, liveProgress = { live }) } } }
    private fun history(n: Int) = (1..n).map {
        ManualHistoryRow(it.toLong(), ManualCopy.historyRow(ToolCopy.HTTP_FINGERPRINT, "10.0.3.$it", "80"), ManualCopy.historyLine("Done", "2 minutes ago"),
            ManualCopy.historyDescription(ToolCopy.HTTP_FINGERPRINT, "10.0.3.$it", "80"))
    }

    @Test fun theRunPillIsPinnedOutsideTheHistoryAndScrollingNeverMovesIt() {
        show(ManualUiState(history = history(30)))
        val pill = rule.onNodeWithTag("manualRun")
        assertFalse(generateSequence(pill.fetchSemanticsNode().parent) { it.parent }.any { SemanticsProperties.VerticalScrollAxisRange in it.config })
        val y = pill.fetchSemanticsNode().positionInRoot.y
        rule.onNodeWithTag("manualHistory").performScrollToIndex(30)
        rule.waitForIdle()
        assertEquals(y, rule.onNodeWithTag("manualRun").fetchSemanticsNode().positionInRoot.y)
        rule.onNodeWithTag("manualRun").performClick()
        assertEquals(listOf("run"), events)
    }
    @Test fun errorsShowUnderTheirFields() {
        show(ManualUiState(form = ManualForm(ipText = "10.0.3", attempted = true), ipError = ManualCopy.ERR_IP, portError = ManualCopy.ERR_PORT_NEEDED))
        rule.onNodeWithText(ManualCopy.ERR_IP).assertExists()
        rule.onNodeWithText(ManualCopy.ERR_PORT_NEEDED).assertExists()
    }
    @Test fun smbDisablesThePortFieldAndSaysWhichPort() {
        show(ManualUiState(form = ManualForm(tool = ToolId.SMB_SESSION), portEnabled = false, showHttps = false, fixedLine = ManualCopy.FIXED_SMB))
        rule.onNodeWithTag("manualPort").assertIsNotEnabled()
        rule.onNodeWithText(ManualCopy.FIXED_SMB).assertExists()
        rule.onNodeWithText(ManualCopy.USE_HTTPS).assertDoesNotExist()
    }
    @Test fun theToolRowListsTheFiveToolsInOrder() {
        show(ManualUiState())
        rule.onNodeWithTag("manualTool").assert(hasText(ManualCopy.toolRow(ToolCopy.HTTP_FINGERPRINT))).performClick()
        val ys = ToolId.entries.map { rule.onNodeWithTag("manualTool:${it.key}").fetchSemanticsNode().positionInRoot.y }
        assertEquals(ys.sorted(), ys)
        rule.onNodeWithTag("manualTool:${ToolId.SNMP_WALK.key}").performClick()
        assertEquals(listOf("tool snmp-walk"), events)
    }
    @Test fun aRunningRunShowsStopAndItsProgressAndLocksTheFields() {
        live = "12 of 300, 2 found  3 s"
        show(ManualUiState(running = true))
        rule.onNodeWithTag("manualRun").assert(hasText(HomeCopy.STOP)).assert(hasContentDescription(ToolCopy.STOP_DESCRIPTION)).performClick()
        rule.onNodeWithTag("manualProgress").assertTextEquals("12 of 300, 2 found  3 s")
        rule.onNodeWithTag("manualIp").assertIsNotEnabled()
        assertEquals(listOf("stop"), events)
    }
    @Test fun aHistoryRowOpensItsRunAndTheSheetShowsItsRoute() {
        show(ManualUiState(history = history(2)))
        rule.onNodeWithTag("historyRow:2").assert(hasContentDescription(ManualCopy.historyDescription(ToolCopy.HTTP_FINGERPRINT, "10.0.3.2", "80"))).performClick()
        assertEquals(listOf("open 2"), events)
        s = s.copy(results = ToolsFakes.results().copy(route = ToolCopy.ROUTE_WIFI))
        rule.waitForIdle()
        rule.onNodeWithTag("routeLine").assertTextEquals("via Wi-Fi")
    }
    @Test fun withNoLanTheFieldsAndRunAreDisabledAndTheLineSaysWhy() {
        show(ManualUiState(lan = false, infoLine = ManualCopy.NO_LAN))
        rule.onNodeWithTag("manualIp").assertIsNotEnabled()
        rule.onNodeWithTag("manualRun").assertIsNotEnabled()
        rule.onNodeWithText(ManualCopy.NO_LAN).assertExists()
    }
    @Test fun anEmptyHistorySaysSo() {
        show(ManualUiState())
        rule.onNodeWithText(ManualCopy.HISTORY_EMPTY).assertExists()
    }
    @Test fun theTitleRowIsToolsWithAWordlistsActionAndThereIsNoBackRow() {   // 3D spec 4
        show(ManualUiState())
        rule.onNodeWithText("Tools").assertIsDisplayed()
        rule.onNodeWithText("Back to Home").assertDoesNotExist(); rule.onNodeWithText("Run a tool").assertDoesNotExist()
        rule.onNodeWithTag("toolsWordlists").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("manage wordlists", events.last())
    }
}
