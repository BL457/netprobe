package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3C spec 8.2 item 4. In-memory state only: the Director's "netprobe" preferences are never opened. */
@RunWith(AndroidJUnit4::class)
class SettingsContentTest {
    @get:Rule val rule = createComposeRule()
    private var s by mutableStateOf(SettingsState())

    private fun show(state: SettingsState = SettingsState(), networkName: String? = "Home") {
        s = state
        rule.setContent { NetProbeTheme {
            SettingsContent(SettingsUi(s, networkName, if (networkName != null) 1L else null, emptyList(), CidrParser.subnetOf("10.0.3.0/24")),
                SettingsActions(update = { f -> s = f(s) }, current = { s }))
        } }
    }
    private fun headingY(text: String) =
        rule.onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNode().positionInRoot.y

    @Test fun resetIsDisabledAtTheDefaults() {
        show()
        rule.onNodeWithTag("settingsMenu").assert(hasContentDescription("Settings menu")).performClick()
        rule.onNodeWithTag("resetDefaults").assertIsNotEnabled()
    }
    @Test fun theOverflowIsPinnedOutsideTheScrollingColumn() {
        show()
        val menu = rule.onNodeWithTag("settingsMenu").fetchSemanticsNode()
        assertFalse(generateSequence(menu.parent) { it.parent }.any { SemanticsProperties.VerticalScrollAxisRange in it.config })
    }
    @Test fun aMovedSliderShowsItsMarkerAndTheMarkerRestoresTheDefault() {
        show(SettingsState(sweepConcurrency = 256))
        rule.onNodeWithTag("default:Sweep connect timeout").assertDoesNotExist()   // at its default: no marker, no space
        rule.onNodeWithTag("default:Sweep concurrency").assert(hasContentDescription("Reset Sweep concurrency to 128"))
            .assert(hasText("Default 128")).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(128, s.sweepConcurrency)
        rule.onNodeWithTag("default:Sweep concurrency").assertDoesNotExist()
    }
    @Test fun resetToDefaultsThenUndoRestoresEveryValueAndKeepsTheKey() {
        val tuned = SettingsState(mdns = false, sweepConcurrency = 256, connectTimeoutMs = 900, maxPrefix = 20, scanTimeoutMs = 1200,
            extEnabled = true, extMax = 8, extGatewayProbe = true, nvdApiKey = "k1")
        show(tuned)
        rule.onNodeWithTag("settingsMenu").performClick()
        rule.onNodeWithTag("resetDefaults").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(SettingsState(nvdApiKey = "k1"), s)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Settings reset to defaults").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertEquals(tuned, s)
    }
    @Test fun theAggressiveSectionSitsDirectlyUnderDiscovery() {
        show()
        val discovery = headingY("Discovery"); val aggressive = headingY(ExtendedCopy.TITLE); val speed = headingY("Speed")
        assertTrue("Discovery $discovery, ${ExtendedCopy.TITLE} $aggressive, Speed $speed", discovery < aggressive && aggressive < speed)
    }
    @Test fun thereIsNoBackRowAndTheTitleRowCarriesTheOverflow() {   // 3D spec 9.2 item 6
        show()
        rule.onNodeWithText("Back to Home").assertDoesNotExist()
        rule.onNode(hasText("Settings") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
        rule.onNodeWithTag("settingsMenu").assertIsDisplayed()
    }
    @Test fun aboutIsTheLastRowAndOpensTheDialogWithThreeLines() {
        val info = AboutInfo("0.3.0", 3, "c8a62e6", "2026-10-21 14:05", "14")
        s = SettingsState()
        rule.setContent { NetProbeTheme {
            SettingsContent(SettingsUi(s, "Home", 1L, emptyList(), CidrParser.subnetOf("10.0.3.0/24"), about = info), SettingsActions(update = { f -> s = f(s) }, current = { s }))
        } }
        rule.onNodeWithTag("aboutRow").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Version 0.3.0 (3)").assertIsDisplayed()          // the row's own version line
        val headings = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().map { it.positionInRoot.y }
        assertEquals("the About section is the last heading", headings.max(), headingY("About"))
        rule.onNodeWithTag("aboutRow").performClick()
        rule.onNodeWithTag("aboutDialog").assertIsDisplayed()
        rule.onNodeWithText("Build c8a62e6, 2026-10-21 14:05").assertIsDisplayed(); rule.onNodeWithText("Android 14").assertIsDisplayed()
        rule.onNodeWithTag("aboutCopy").assertIsDisplayed()
        rule.onNodeWithTag("aboutClose").performClick(); rule.onNodeWithTag("aboutDialog").assertDoesNotExist()
    }
}
