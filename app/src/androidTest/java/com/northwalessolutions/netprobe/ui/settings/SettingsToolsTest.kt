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
}
