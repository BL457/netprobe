package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.DeviceEdits
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
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
    private val edits = mutableListOf<DeviceEdits>()
    private var scans = 0; private var stops = 0
    private val recording get() = DetailActions(onAction = { actions += it }, onScan = { scans++ }, onStop = { stops++ }, onEdit = { edits += it })
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
    @Test fun longPressOnTheActionWordOpensTheSheetAndRunsNothing() {   // fix round 1, I1
        show(DetailFakes.state(listOf(DetailFakes.port(22, "ssh", name = "SSH"))))
        rule.onNodeWithTag("portAction").performTouchInput { longClick() }
        rule.onNodeWithText("Copy IP and port").assertIsDisplayed()
        assertTrue("actions: $actions", actions.isEmpty())
    }
    @Test fun scanPillKeepsItsWidthWhenItTurnsToStop() {   // fix round 1, pill width
        var st by mutableStateOf(DetailFakes.state())
        rule.setContent { NetProbeTheme { DeviceConfigurationOverride(phone) { DetailContent(st, recording) } } }
        val before = rule.onNodeWithTag("detailScanButton").getBoundsInRoot()
        st = st.copy(scanning = true)
        rule.waitForIdle()
        assertEquals(before, rule.onNodeWithTag("detailScanButton").getBoundsInRoot())
        rule.onNodeWithTag("detailScanButton").assertTextEquals("Stop")
    }
    @Test fun expandedNotePutsTheCursorAtTheEnd() {   // fix round 1, note cursor
        val note = "QA note NAS"
        show(DetailFakes.state(note = note), noteExpanded = true)
        rule.onNode(hasTestTag("note") and hasSetTextAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(note.length)))
    }
    @Test fun segmentLabelsStayOnOneLineAtFontScale13OnAPixel7Pro() {   // on-device QA: "Investigated" broke mid-word at 1.3 on the Pixel 7 Pro
        val pixel7Pro = DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 820.dp))   // 1440 px at 560 dpi
        var st by mutableStateOf(DetailFakes.state())
        rule.setContent { NetProbeTheme { DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.3f) then pixel7Pro) { DetailContent(st, recording) } } }
        for (status in HostStatus.values()) {   // each label is checked inactive (W400) and active (W500, the widest)
            st = st.copy(host = DetailFakes.host(status = status)); rule.waitForIdle()
            for (label in listOf("Unmarked", "Interesting", "Investigated", "Ignored", "Quick", "Full", "Custom")) {
                val results = mutableListOf<TextLayoutResult>()
                rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                assertEquals("$label lines with $status active", 1, results.first().lineCount)
            }
        }
    }
    @Test fun softwareChipsShowUnderTheDataLineAndLeaveFivePortRowsAt13() {
        show(DetailFakes.state().copy(host = DetailFakes.host().copy(identSoftware = "Home Assistant\nGrafana")), fontScale = 1.3f)
        rule.onNodeWithTag("softwareChips").assertIsDisplayed()
        rule.onNodeWithText("Runs Home Assistant", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Runs Grafana", useUnmergedTree = true).assertExists()
        assertTrue("rows: ${fullyVisibleRows()}", fullyVisibleRows() >= 5)
    }
    @Test fun noSoftwareMeansNoChipRow() {
        show(DetailFakes.state())
        rule.onNodeWithTag("softwareChips").assertDoesNotExist()
    }

    /** Nothing detected, so every value the test types is an override. */
    private fun bareHost() = DetailFakes.host().copy(deviceType = DeviceType.UNKNOWN, identVendor = null, identModel = null)

    @Test fun editDeviceFromTheIdentityLineSavesOnlyTheOverrides() {
        var st by mutableStateOf(DetailFakes.state().copy(host = bareHost()))
        rule.setContent { NetProbeTheme { DeviceConfigurationOverride(phone) { DetailContent(st, recording) } } }
        rule.onNodeWithTag("identityLine").assert(hasContentDescription("Edit device, CHNAS")).performClick()
        rule.onNodeWithText("Edit device").assertIsDisplayed()
        rule.onNodeWithTag("editType").performClick(); rule.onNodeWithTag("type:NAS").performClick()
        rule.onNodeWithTag("editVendor").performTextReplacement("Synology")
        rule.onNodeWithTag("editSave").performClick()
        assertEquals(listOf(DeviceEdits(type = DeviceType.NAS, vendor = "Synology")), edits)
        // Reopened with those overrides stored: "Reset to detected" shows on Type and Vendor only.
        st = st.copy(host = bareHost().copy(userType = DeviceType.NAS, userVendor = "Synology"))
        rule.onNodeWithTag("detailName").assert(hasContentDescription("Edit device, CHNAS")).performClick()
        rule.onNodeWithTag("resetType").assertExists(); rule.onNodeWithTag("resetVendor").assertExists()
        rule.onNodeWithTag("resetName").assertDoesNotExist(); rule.onNodeWithTag("resetModel").assertDoesNotExist()
        rule.onNodeWithTag("resetType").performClick(); rule.onNodeWithTag("editSave").performClick()
        assertEquals(DeviceEdits(vendor = "Synology"), edits.last())
    }
    @Test fun theOwnPhonesNameFieldStartsWithTheDetectedName() {
        show(DetailFakes.state().copy(displayName = "This phone"))
        rule.onNodeWithTag("detailName").performClick()
        rule.onNodeWithTag("editName").assert(hasText("CHNAS")); rule.onNodeWithTag("editName").assert(!hasText("This phone"))
        rule.onNodeWithTag("editSave").performClick()
        assertEquals(DeviceEdits(), edits.single())
    }
    @Test fun editButtonsStayVisibleWithTheBodyScrolledAtFontScale20() {
        show(DetailFakes.state(), fontScale = 2f)
        rule.onNodeWithTag("detailName").performClick()
        rule.onNodeWithTag("editBody").performScrollToNode(hasTestTag("editModel"))
        rule.onNodeWithTag("editSave").assertIsDisplayed(); rule.onNodeWithTag("editCancel").assertIsDisplayed()
    }
    @Test fun overflowOffersEditDevice() {
        show(DetailFakes.state())
        rule.onNodeWithTag("detailMenu").assert(hasContentDescription("More options")).performClick()
        rule.onNodeWithText("Edit device").assertIsEnabled().performClick()
        rule.onNodeWithTag("editSave").assertIsDisplayed()
    }
    @Test fun whyThisIdentityStartsWithSetByYou() {
        show(DetailFakes.state().copy(host = DetailFakes.host().copy(userType = DeviceType.NAS, userModel = "DS220+")))
        rule.onNodeWithText("Why this identity").performClick()
        rule.onNodeWithText("Set by you: type NAS, model DS220+").assertIsDisplayed()
    }
    private val tryAsChip = SemanticsMatcher("a Try as chip") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tryAs:") == true }
    @Test fun portSheetOffersOpenWithAndTryAsChips() {
        show(DetailFakes.state(listOf(DetailFakes.port(8090, "http", tryAs = listOf("http", "https", "ssh")))))
        rule.onNodeWithTag("portAction").assertTextEquals("Open")
        rule.onNodeWithText("8090").performTouchInput { longClick() }
        rule.onNodeWithText("Open with...").assertIsDisplayed(); rule.onNodeWithText("Try as").assertIsDisplayed()
        rule.onAllNodes(tryAsChip).assertCountEquals(3)
        rule.onAllNodesWithText("No app installed", substring = true).assertCountEquals(0)
        rule.onNodeWithTag("tryAs:ssh").assert(hasContentDescription("Open 10.0.3.59:8090 as ssh")).performClick()
        assertEquals(DeviceAction.OpenWith("ssh://10.0.3.59:8090"), actions.last())
        rule.onNodeWithText("Open with...").assertDoesNotExist()
    }
    @Test fun noHandlersMeansNoTryAsRow() {
        show(DetailFakes.state(listOf(DetailFakes.port(8090, "http"))))
        rule.onNodeWithText("8090").performTouchInput { longClick() }
        rule.onNodeWithText("Open with...").assertIsDisplayed(); rule.onNodeWithText("Try as").assertDoesNotExist()
    }
    @Test fun overflowRemoveLeavesTheStopToTheRemoveAndGoesBack() {
        val events = mutableListOf<String>()
        rule.setContent { NetProbeTheme { DeviceConfigurationOverride(phone) {
            DetailContent(DetailFakes.state(scanning = true), DetailActions(onStop = { events += "stop" }, onRemove = { events += "remove" }, onBack = { events += "back" }))
        } } }
        rule.onNodeWithTag("detailMenu").performClick()
        rule.onNodeWithText("Edit device").assertIsEnabled()
        rule.onNodeWithText("Remove device").assertIsEnabled().performClick()
        assertEquals(listOf("remove", "back"), events)
    }
    private fun showTools(state: DetailUiState, tools: com.northwalessolutions.netprobe.ui.tools.DetailToolsState,
                          toolActions: com.northwalessolutions.netprobe.ui.tools.ToolActions = com.northwalessolutions.netprobe.ui.tools.ToolActions()) = rule.setContent {
        NetProbeTheme { DeviceConfigurationOverride(phone) { DetailContent(state, recording, toolState = tools, toolActions = toolActions) } }
    }
    @Test fun thePinnedBarShowsTheToolRunAndItsStop() {
        var stopped = 0
        showTools(DetailFakes.state(), com.northwalessolutions.netprobe.ui.tools.DetailToolsState(
            bar = com.northwalessolutions.netprobe.ui.tools.ToolRunBar("Directory brute-force on 5000", "1234 of 4614, 7 found  12 s", 0.27f),
            busy = com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry.Work.TOOL),
            com.northwalessolutions.netprobe.ui.tools.ToolActions(onStopTool = { stopped++ }))
        rule.onNodeWithTag("toolBarLabel").assertTextEquals("Directory brute-force on 5000")
        rule.onNodeWithTag("toolBarProgress").assertTextEquals("1234 of 4614, 7 found  12 s")
        rule.onNodeWithTag("detailScanButton").assert(hasContentDescription("Stop tool run")).performClick()
        assertEquals(1, stopped); assertEquals(0, scans)
        rule.onNodeWithText("Full").assertIsNotEnabled()
    }
    @Test fun thePortSheetShowsRunToolRowsOnlyForApplicableTools() {
        val ran = mutableListOf<Pair<String, Int>>()
        showTools(DetailFakes.state(listOf(DetailFakes.port(5000, "http"), DetailFakes.port(22, "ssh", name = "SSH"))),
            com.northwalessolutions.netprobe.ui.tools.DetailToolsState(portTools = mapOf(5000 to listOf(
                com.northwalessolutions.netprobe.ui.tools.PortTool("dir-bruteforce", "Directory brute-force"), com.northwalessolutions.netprobe.ui.tools.PortTool("http-fingerprint", "HTTP fingerprint")))),
            com.northwalessolutions.netprobe.ui.tools.ToolActions(onRunTool = { t, p -> ran += t to p }))
        rule.onNodeWithText("5000").performTouchInput { longClick() }
        rule.onNodeWithText("Run tool").assertIsDisplayed()
        rule.onNodeWithTag("sheetTool:dir-bruteforce").assertIsDisplayed(); rule.onNodeWithTag("sheetTool:http-fingerprint").assertIsDisplayed()
        rule.onNodeWithTag("sheetTool:smb-session").assertDoesNotExist()
        rule.onNodeWithTag("sheetTool:http-fingerprint").performClick()
        assertEquals(listOf(com.northwalessolutions.netprobe.engine.tools.ToolKeys.HTTP_FINGERPRINT to 5000), ran)
        rule.onNodeWithText("22").performTouchInput { longClick() }
        rule.onNodeWithText("Run tool").assertDoesNotExist()
    }
    @Test fun theIdentifyActionSitsInThePortRowAndRunsTheIdentifyTool() {   // Director ruling R2
        val ran = mutableListOf<Pair<String, Int>>()
        showTools(DetailFakes.state(listOf(DetailFakes.port(5000, "http"), DetailFakes.port(22, "ssh", name = "SSH"))),
            com.northwalessolutions.netprobe.ui.tools.DetailToolsState(), com.northwalessolutions.netprobe.ui.tools.ToolActions(onRunTool = { t, p -> ran += t to p }))
        rule.onAllNodesWithTag("portIdentify")[1].performClick()
        assertEquals(listOf(com.northwalessolutions.netprobe.engine.tools.ToolKeys.IDENTIFY to 22), ran)
        assertEquals(0, actions.size)
    }
    @Test fun aRunningIdentifyShowsIdentifyingOnItsOwnPortOnly() {
        showTools(DetailFakes.state(listOf(DetailFakes.port(5000, "http"), DetailFakes.port(22, "ssh", name = "SSH"))),
            com.northwalessolutions.netprobe.ui.tools.DetailToolsState(runTool = com.northwalessolutions.netprobe.engine.tools.ToolKeys.IDENTIFY, runPort = 22,
                busy = com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry.Work.TOOL))
        rule.onAllNodesWithText("Identifying").assertCountEquals(1)
        rule.onAllNodesWithText("Identify").assertCountEquals(1)
    }
    @Test fun identifyAllServicesIsInTheOverflowAndRunsOverTheWholeDevice() {
        val ran = mutableListOf<Pair<String, Int>>()
        showTools(DetailFakes.state(), com.northwalessolutions.netprobe.ui.tools.DetailToolsState(),
            com.northwalessolutions.netprobe.ui.tools.ToolActions(onRunTool = { t, p -> ran += t to p }))
        rule.onNodeWithTag("detailMenu").performClick()
        rule.onNodeWithText("Identify all services").performClick()
        assertEquals(listOf(com.northwalessolutions.netprobe.engine.tools.ToolKeys.IDENTIFY_ALL to 0), ran)
    }
    @Test fun longPressOnTheHeaderOffersTheDeviceCopyMenu() {   // Director ruling R4
        show(DetailFakes.state())
        rule.onNodeWithTag("detailName").performTouchInput { longClick() }
        listOf("IP", "MAC", "NAME", "DETAILS").forEach { rule.onNodeWithTag("copy:$it").assertIsDisplayed() }
    }
    @Test fun theLongPressSheetOfAPortStartsWithCopyAddress() {
        show(DetailFakes.state(listOf(DetailFakes.port(22, "ssh", name = "SSH"))))
        rule.onNodeWithText("22").performTouchInput { longClick() }
        rule.onNodeWithTag("copyAddress").assertIsDisplayed()
    }
    @Test fun removeDeviceIsDisabledWhileAToolHoldsTheHost() {
        showTools(DetailFakes.state(), com.northwalessolutions.netprobe.ui.tools.DetailToolsState(busy = com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry.Work.TOOL, removeAllowed = false))
        rule.onNodeWithTag("detailMenu").performClick()
        rule.onNodeWithText("Remove device").assertIsNotEnabled()
    }
    @Test fun theToolsBlockIsTheFirstListItem() {
        showTools(DetailFakes.state(), com.northwalessolutions.netprobe.ui.tools.DetailToolsState(tools = com.northwalessolutions.netprobe.ui.tools.ToolsFakesForDetail.tools))
        val header = rule.onNodeWithTag("toolsHeader").getBoundsInRoot(); val firstPort = rule.onAllNodesWithTag("portAction")[0].getBoundsInRoot()
        assertTrue(header.bottom <= firstPort.top)
    }

    @Test fun scanIsDisabledAndTheRefusalShowsWhileTwoPortScansRunElsewhere() {   // 3D spec 9.2 item 7
        show(DetailFakes.state().copy(scanRefusal = DetailCopy.portScansTooMany(2)))
        rule.onNodeWithTag("detailScanButton").assertIsNotEnabled()
        rule.onNodeWithTag("scanRefusal").assertIsDisplayed().assertTextEquals("Two port scans are already running, stop one in Scans")
    }
    @Test fun aScanRunningHereKeepsItsStopEnabledAndShowsNoRefusal() {
        show(DetailFakes.state(scanning = true).copy(progress = ScanProgress(1, 10, 0, 0)))
        rule.onNodeWithTag("detailScanButton").assertIsEnabled()
        rule.onNodeWithTag("scanRefusal").assertDoesNotExist()
    }
    @Test fun theBackRowNamesWhereBackGoes() {   // 3D spec 9.2 and D18
        rule.setContent { NetProbeTheme { DetailContent(DetailFakes.state(), recording, backLabel = "Back to Scans") } }
        rule.onNodeWithText("Back to Scans").assertIsDisplayed()
        rule.onNodeWithText("Back to Home").assertDoesNotExist()
    }
    @Test fun theBackRowDefaultsToDevices() {
        show(DetailFakes.state())
        rule.onNodeWithText("Back to Devices").assertIsDisplayed()
    }
}
