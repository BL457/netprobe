package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRowsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sectionIsAHeadingAndRowsMeetTheTouchFloor() {
        var on = false
        rule.setContent { NetProbeTheme { Column {
            Section("Discovery")
            SwitchRow("mDNS (Bonjour)", checked = false, supporting = "Bonjour names and services") { on = it }
            StepSlider("Sweep concurrency", 128, 32, 512, 32, "") { }
        } } }
        rule.onNode(hasText("Discovery") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        rule.onNodeWithText("Bonjour names and services").assertIsDisplayed()
        val row = rule.onNode(hasText("mDNS (Bonjour)", substring = true) and hasClickAction()).getBoundsInRoot()
        assertTrue(row.bottom - row.top >= 56.dp)
        rule.onNode(hasText("mDNS (Bonjour)", substring = true) and hasClickAction()).performClick(); assertTrue(on)
        rule.onNodeWithText("128").assertIsDisplayed()
    }
    @Test fun disabledRowIgnoresTaps() {
        var calls = 0
        rule.setContent { NetProbeTheme { SwitchRow("Probe for gateways", checked = false, enabled = false) { calls++ } } }
        rule.onNode(hasText("Probe for gateways", substring = true) and hasClickAction()).assertIsNotEnabled().performClick()
        assertEquals(0, calls)
    }
}
