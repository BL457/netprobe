package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** spec 12.2 item 4. */
@RunWith(AndroidJUnit4::class)
class DirBruteSheetTest {
    @get:Rule val rule = createComposeRule()
    private val runs = mutableListOf<DirBruteOptions>(); private var manage = 0
    private fun show(state: DirBruteSheetState = ToolsFakes.sheet(), fontScale: Float = 1f) = rule.setContent {
        NetProbeTheme { DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 820.dp))) {
            DirBruteSheet(state, onRun = { runs += it }, onManageWordlists = { manage++ }, onDismiss = {})
        } }
    }

    @Test fun defaultsAreShownAndRunReturnsThem() {
        show()
        rule.onNodeWithText("Directory brute-force").assertIsDisplayed(); rule.onNodeWithText("http://10.0.3.10:5000/").assertIsDisplayed()
        rule.onNodeWithTag("wordlist:builtin:common-short").assertIsSelected()
        rule.onNodeWithText("298 paths").assertIsDisplayed()
        rule.onNodeWithTag("toolRunPill").assertIsEnabled().performClick()
        assertEquals(listOf(DirBruteOptions()), runs)
    }
    @Test fun extensionsMultiplyTheTotalAndInvalidInputDisablesRun() {
        show()
        rule.onNodeWithTag("extensionsField").performTextInput("php, html")
        rule.onNodeWithText("894 paths").assertIsDisplayed()
        rule.onNodeWithTag("extensionsField").performTextClearance(); rule.onNodeWithTag("extensionsField").performTextInput("ph p")
        rule.onNodeWithText("Use letters and digits only, for example php, html").assertExists()
        rule.onNodeWithTag("toolRunPill").assertIsNotEnabled()
        rule.onNodeWithTag("extensionsField").performTextClearance()
        rule.onNodeWithTag("codesField").performTextClearance(); rule.onNodeWithTag("codesField").performTextInput("99")
        rule.onNodeWithText("Use codes from 100 to 599, for example 200, 301, 403").assertExists()
        rule.onNodeWithTag("toolRunPill").assertIsNotEnabled()
        rule.onNodeWithTag("codesField").performTextClearance()
        rule.onNodeWithText("Add at least one status code").assertExists()
    }
    @Test fun aBusyHostShowsItsReasonAndDisablesRun() {
        show(ToolsFakes.sheet(busyReason = ToolCopy.BUSY_TOO_MANY))
        rule.onNodeWithText("Four tools are already running, stop one first").assertIsDisplayed()
        rule.onNodeWithTag("toolRunPill").assertIsNotEnabled()
    }
    @Test fun theFooterAndRunPillStayVisibleWithTheBodyScrolledToTheEndAtFontScale13() {
        show(fontScale = 1.3f)
        rule.onNodeWithTag("filterSwitch").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("toolRunPill").assertIsDisplayed()
        rule.onNodeWithText("Directory brute-force").assertIsDisplayed()
        val pill = rule.onNodeWithTag("toolRunPill").fetchSemanticsNode()
        val body = rule.onNodeWithTag("dirBruteBody").fetchSemanticsNode()
        assertFalse("the Run pill must not be inside the scrolling body", generateSequence(pill.parent) { it.parent }.any { it.id == body.id })
    }
    @Test fun aChosenListAndTypedExtensionReachRun() {
        show()
        rule.onNodeWithTag("wordlist:builtin:admin-panels").performClick()
        rule.onNodeWithTag("extensionsField").performTextInput("php")
        rule.onNodeWithTag("toolRunPill").assertIsEnabled().performClick()
        assertEquals(1, runs.size)
        assertEquals("builtin:admin-panels", runs[0].wordlistId)
        assertEquals(listOf("php"), runs[0].extensions)
    }
    @Test fun manageWordlistsFiresItsLambda() {
        show(); rule.onNodeWithTag("manageWordlists").performClick(); assertEquals(1, manage)
    }
}
