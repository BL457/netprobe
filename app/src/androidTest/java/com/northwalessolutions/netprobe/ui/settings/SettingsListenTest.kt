package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3C spec 3.1 on the stateless Settings content: in-memory state only, the Director's preferences are never opened. */
@RunWith(AndroidJUnit4::class)
class SettingsListenTest {
    @get:Rule val rule = createComposeRule()
    private var s by mutableStateOf(SettingsState())

    private fun show(state: SettingsState, status: String?) {
        s = state
        rule.setContent { NetProbeTheme { SettingsContent(SettingsUi(s, "Home", 1L, listenStatus = status), SettingsActions(update = { f -> s = f(s) }, current = { s })) } }
    }

    @Test fun theSwitchSitsAfterHttpPageTitlesIsOffByDefaultAndShowsNoStatus() {
        show(SettingsState(), ListenCopy.BOTH)
        val http = rule.onNodeWithText("HTTP page titles").fetchSemanticsNode().positionInRoot.y
        val listen = rule.onNodeWithText(ListenCopy.LABEL).fetchSemanticsNode().positionInRoot.y
        assertTrue("HTTP page titles $http, listen $listen", http < listen)
        rule.onNodeWithText(ListenCopy.SUPPORT, substring = true).assertExists()
        rule.onNodeWithTag("listenStatus").assertDoesNotExist()
    }
    @Test fun turningItOnSavesItAndShowsTheStatusLine() {
        show(SettingsState(), ListenCopy.PAUSED_SCAN)
        rule.onNodeWithText(ListenCopy.LABEL).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(s.listenMulticast)
        rule.onNodeWithTag("listenStatus").assertTextEquals(ListenCopy.PAUSED_SCAN)
    }
    @Test fun resetToDefaultsTurnsListeningOff() {
        show(SettingsState(listenMulticast = true), ListenCopy.BOTH)
        rule.onNodeWithTag("settingsMenu").performClick()
        rule.onNodeWithTag("resetDefaults").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertFalse(s.listenMulticast)
        rule.onNodeWithTag("listenStatus").assertDoesNotExist()
    }
}
