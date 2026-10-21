package com.northwalessolutions.netprobe.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3D spec 9.2 item 2: the Scans badge on a fake running count. */
@RunWith(AndroidJUnit4::class)
class NavBadgeTest {
    @get:Rule val rule = createComposeRule()
    private fun show(running: Int) = rule.setContent { NetProbeTheme { NetProbeBar(selected = Tab.DEVICES, scansRunning = running, onSelect = {}) } }

    @Test fun noBadgeAtZero() { show(0); rule.onNodeWithTag("scansBadge").assertDoesNotExist() }
    @Test fun twoRunningShowsTwoAndItsDescription() {
        show(2)
        rule.onNodeWithTag("scansBadge", useUnmergedTree = true).assertTextEquals("2")
        rule.onNodeWithTag("tab:scans").assert(hasContentDescription("Scans, 2 running"))
    }
    @Test fun eightRunningShowsEight() {
        show(8)
        rule.onNodeWithTag("scansBadge", useUnmergedTree = true).assertTextEquals("8")
        rule.onNodeWithTag("tab:scans").assert(hasContentDescription("Scans, 8 running"))
    }
    @Test fun tenRunningCapsAtNinePlus() {
        show(10)
        rule.onNodeWithTag("scansBadge", useUnmergedTree = true).assertTextEquals("9+")
    }
}
