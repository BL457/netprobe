package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** spec 12.2 item 7. */
@RunWith(AndroidJUnit4::class)
class SettingsToolsTest {
    @get:Rule val rule = createComposeRule()
    @Test fun theToolsSectionShowsTheWordlistsRowAndOpensIt() {
        var opened = 0
        rule.setContent { NetProbeTheme { ToolsSection(imported = 2, onOpenWordlists = { opened++ }) } }
        rule.onNodeWithText("Tools").assertIsDisplayed(); rule.onNodeWithText("3 built in, 2 imported").assertIsDisplayed()
        rule.onNodeWithTag("wordlistsRow").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(56f)).performClick()
        assertEquals(1, opened)
    }
    @Test fun whileTheStoreLoadsItNeverClaimsZeroImported() {
        rule.setContent { NetProbeTheme { ToolsSection(imported = null, onOpenWordlists = {}) } }
        rule.onNodeWithText("3 built in").assertIsDisplayed(); rule.onNodeWithText("0 imported", substring = true).assertDoesNotExist()
    }
    @Test fun importToolOpensTheImportFlow() {
        var opened = 0
        rule.setContent { NetProbeTheme { ToolsSection(imported = 0, onOpenWordlists = {}, onImportTool = { opened++ }) } }
        rule.onNodeWithTag("importToolRow").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(56f)).performClick()
        assertEquals(1, opened)
    }
    @Test fun myToolsListsWorkingAndBrokenToolsAndRemoveAsksFirst() {
        val removed = ArrayList<String>()
        val rows = listOf(
            com.northwalessolutions.netprobe.ui.tools.MyToolRow("user:a-tool", "A tool", "Version 1.0, contacts your network", null),
            com.northwalessolutions.netprobe.ui.tools.MyToolRow("user:b-tool", "B tool", "Broken: The script file is missing", "The script file is missing"),
        )
        rule.setContent { NetProbeTheme { ToolsSection(imported = 0, onOpenWordlists = {}, myTools = rows, onRemoveTool = { removed += it }) } }
        rule.onNodeWithText("Version 1.0, contacts your network").assertIsDisplayed()
        rule.onNodeWithText("Broken: The script file is missing").assertIsDisplayed()
        rule.onNodeWithTag("myToolRemove:user:b-tool").performClick()
        assertEquals(emptyList<String>(), removed)
        rule.onNodeWithTag("removeCancel").performClick()
        assertEquals(emptyList<String>(), removed)
        rule.onNodeWithTag("myToolRemove:user:b-tool").performClick()
        rule.onNodeWithTag("removeConfirm").performClick()
        assertEquals(listOf("user:b-tool"), removed)
    }
    @Test fun withNoImportedToolsTheListSaysSo() {
        rule.setContent { NetProbeTheme { ToolsSection(imported = 0, onOpenWordlists = {}) } }
        rule.onNodeWithTag("myToolsEmpty").assertIsDisplayed()
    }
}
