package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.engine.portscan.ScanProgress
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailContentTest {
    @get:Rule val rule = createComposeRule()
    private val actions = mutableListOf<DeviceAction>()
    private var scans = 0; private var stops = 0
    private val recording get() = DetailActions(onAction = { actions += it }, onScan = { scans++ }, onStop = { stops++ })
    private val phone = DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 820.dp))
    private val longNote = "Line one of the note\nLine two of the note\nLine three of the note\nLine four of the note"

    private fun show(state: DetailUiState, fontScale: Float = 1f, noteExpanded: Boolean = false) = rule.setContent {
        NetProbeTheme { DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale) then phone) {
            DetailContent(state, recording, startNoteExpanded = noteExpanded)
        } }
    }
    /** Port rows whose action word lies wholly inside the list viewport (pixels on both sides). */
    private fun fullyVisibleRows(): Int {
        val listBottom = rule.onNodeWithTag("portList").fetchSemanticsNode().boundsInRoot.bottom
        return rule.onAllNodesWithTag("portAction").fetchSemanticsNodes().count { it.boundsInRoot.bottom <= listBottom + 0.5f }
    }

    @Test fun pinnedBlockStaysWhileThePortsScroll() {   // spec 10.2 test 2
        show(DetailFakes.state())
        val header = rule.onNodeWithTag("detailHeader").getBoundsInRoot(); val bar = rule.onNodeWithTag("detailScanBar").getBoundsInRoot()
        rule.onNodeWithTag("portList").performScrollToIndex(29)
        assertEquals(header, rule.onNodeWithTag("detailHeader").getBoundsInRoot()); assertEquals(bar, rule.onNodeWithTag("detailScanBar").getBoundsInRoot())
        rule.onNodeWithTag("detailScanButton").assertIsEnabled().performClick(); assertEquals(1, scans)
        rule.onNode(hasTestTag("detailScanButton") and hasAnyAncestor(hasTestTag("portList"))).assertDoesNotExist()
    }
    @Test fun identityBlockAsDrawn() {
        show(DetailFakes.state())
        rule.onNodeWithText("Synology DS220+  NAS").assertIsDisplayed()
        rule.onNodeWithText("10.0.3.59  00:11:32:9a:1f:e4").assertIsDisplayed()
        rule.onNodeWithText("Why this identity").assertIsDisplayed(); rule.onNodeWithText("Look up vulnerabilities").assertIsDisplayed()
        rule.onNodeWithTag("statusSegments").onChildren().filter(isSelected()).assertCountEquals(1)
        rule.onNodeWithText("Add a note", useUnmergedTree = true).assertIsDisplayed()
    }
    @Test fun portActionWordsAndTheCopyFallback() {
        show(DetailFakes.state(listOf(DetailFakes.port(80, "http"), DetailFakes.port(22, "ssh", name = "SSH"), DetailFakes.port(445, "smb", name = "SMB"),
            DetailFakes.port(5357, "http", has = false))))
        val words = rule.onAllNodesWithTag("portAction")   // the action box is its own clickable, so it is not merged into the row
        words[0].assertTextEquals("Open"); words[1].assertTextEquals("Connect"); words[2].assertTextEquals("Open share"); words[3].assertTextEquals("Copy")
        rule.onNode(hasTestTag("portAction") and hasContentDescription("Copy 10.0.3.59:5357")).performClick()
        assertEquals(DeviceAction.Copy("Copy IP:port", "10.0.3.59:5357"), actions.last())
    }
    @Test fun longPressOpensTheSheetWithoutTheNoAppEntry() {
        show(DetailFakes.state(listOf(DetailFakes.port(5357, "http", has = false))))
        rule.onNodeWithText("5357").performTouchInput { longClick() }
        rule.onNodeWithText("Copy IP and port").assertIsDisplayed(); rule.onNodeWithText("Open with...").assertIsDisplayed()
        rule.onNodeWithText("Look up vulnerabilities for this service").assertIsDisplayed()
        rule.onAllNodesWithText("No app installed", substring = true).assertCountEquals(0)
    }
    @Test fun whyThisIdentityOpensASheet() {
        show(DetailFakes.state())
        rule.onNodeWithText("Why this identity").performClick()
        rule.onNodeWithText("mDNS model DS220+").assertIsDisplayed()
    }
    @Test fun collapsedNoteAtFontScale13ShowsSixPortRows() {   // spec 10.2 test 7, first acceptance
        show(DetailFakes.state(), fontScale = 1.3f)
        assertTrue("rows: ${fullyVisibleRows()}", fullyVisibleRows() >= 6)
    }
    @Test fun worstCaseAtFontScale13LeavesThreePortRows() {   // Review Focus 2, spec 10.2 test 7 second acceptance
        show(DetailFakes.state(note = longNote, profile = ScanProfile.Custom(emptyList()), customText = "22,abc", customError = "Not a port or range: 'abc'"),
            fontScale = 1.3f, noteExpanded = true)
        rule.onNodeWithText("Not a port or range: 'abc'").assertIsDisplayed()
        rule.onNodeWithTag("detailScanButton").assertIsNotEnabled()
        assertTrue("rows: ${fullyVisibleRows()}", fullyVisibleRows() >= 3)
    }
    @Test fun scanBarHeightDoesNotJumpWhenScanStarts() {   // Review Focus 2, spec 10.2 test 7 third acceptance
        var st by mutableStateOf(DetailFakes.state())
        rule.setContent { NetProbeTheme { DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.3f) then phone) { DetailContent(st, recording) } } }
        val before = rule.onNodeWithTag("detailScanBar").getBoundsInRoot()
        st = st.copy(scanning = true, progress = ScanProgress(312, 65535, 4, 0))
        rule.waitForIdle()
        val after = rule.onNodeWithTag("detailScanBar").getBoundsInRoot()
        assertEquals(before.bottom - before.top, after.bottom - after.top)
        rule.onNodeWithText("312 of 65535, found 4").assertIsDisplayed()
        rule.onNodeWithTag("detailScanButton").performClick(); assertEquals(1, stops)
    }
    @Test fun tappingTheListCollapsesTheExpandedNote() {   // spec 5.1 item 7: Compose does not clear focus on a tap elsewhere by itself
        show(DetailFakes.state(ports = DetailFakes.ports(2), note = longNote), noteExpanded = true)
        rule.onNode(hasTestTag("note") and hasSetTextAction()).assertIsDisplayed()
        rule.onNodeWithTag("portList").performTouchInput { click(Offset(width / 2f, height - 10f)) }   // blank space under the two rows
        rule.waitForIdle()
        rule.onNode(hasTestTag("note") and hasSetTextAction()).assertDoesNotExist()   // the collapsed box has no text-entry action
        rule.onNodeWithTag("note").assertIsDisplayed()
    }
}
