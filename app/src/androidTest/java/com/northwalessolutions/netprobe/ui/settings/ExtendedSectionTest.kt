package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.data.ManualRangeStore
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtendedSectionTest {
    @get:Rule val rule = createComposeRule()
    private val prefs = HashMap<String, String>()
    private val store = ManualRangeStore({ prefs[it] }, { k, v -> if (v == null) prefs.remove(k) else prefs[k] = v })
    private val local = CidrParser.subnetOf("10.0.3.0/24")!!

    private fun show(enabled: Boolean = true, networkId: Long? = 1, name: String? = "Home", cap: Int = 4) = rule.setContent {
        var s by remember { mutableStateOf(SettingsState(extEnabled = enabled, extMax = cap)) }
        val rev by store.revision.collectAsState()
        val manual = remember(rev) { store.forNetwork(networkId) }
        NetProbeTheme { Column {
            ExtendedSection(s, { f -> s = f(s) }, name, manual, local) { list -> networkId?.let { store.set(it, list) } }
        } }
    }

    @Test fun editorNotesResetWhenTheNetworkChanges() {   // Task 17 M2
        var id by mutableStateOf<Long?>(1)
        rule.setContent {
            var s by remember { mutableStateOf(SettingsState(extEnabled = true, extMax = 4)) }
            val rev by store.revision.collectAsState()
            val manual = remember(id, rev) { store.forNetwork(id) }
            NetProbeTheme { Column {
                ExtendedSection(s, { f -> s = f(s) }, if (id == 1L) "Home" else "Office", manual, local, networkId = id) { list -> id?.let { store.set(it, list) } }
            } }
        }
        repeat(2) {
            rule.onNodeWithTag("addRange").performTextInput("10.0.4.0/24")
            rule.onNodeWithTag("addRangeButton").performClick()
        }
        rule.onNodeWithText("Duplicate removed: 10.0.4.0/24").assertIsDisplayed()
        id = 2; rule.waitForIdle()
        rule.onNodeWithText("Duplicate removed: 10.0.4.0/24").assertDoesNotExist()
    }
    @Test fun rejectsABadCidrInlineAndKeepsAddDisabled() {
        show()
        rule.onNodeWithTag("addRange").performTextInput("8.8.8.0/24")
        rule.onNodeWithText("Only private ranges are allowed").assertIsDisplayed()
        rule.onNodeWithTag("addRangeButton").assertIsNotEnabled()
    }
    @Test fun persistsAGoodRangeForTheCurrentNetworkOnly() {
        store.set(2, listOf(CidrParser.subnetOf("172.16.9.0/24")!!))
        show(networkId = 1)
        rule.onNodeWithTag("addRange").performTextInput("10.0.4.20/24")
        rule.onNodeWithText("Normalised to 10.0.4.0/24").assertIsDisplayed()
        rule.onNodeWithTag("addRangeButton").performClick()
        assertEquals(listOf(CidrParser.subnetOf("10.0.4.0/24")!!), store.get(1))
        assertEquals(listOf(CidrParser.subnetOf("172.16.9.0/24")!!), store.get(2))
        rule.onNode(hasTestTag("manualRange") and hasText("10.0.4.0/24")).assertIsDisplayed()   // the list row; the field's placeholder shows the same text
        rule.onNodeWithContentDescription("Remove 10.0.4.0/24").performClick()
        assertTrue(store.get(1).isEmpty())
    }
    @Test fun ownNetworkIsRejected() {
        show(); rule.onNodeWithTag("addRange").performTextInput("10.0.3.16/28")
        rule.onNodeWithText("This is your own network").assertIsDisplayed()
    }
    @Test fun noNetworkDisablesTheEditor() {
        show(networkId = null, name = null)
        rule.onNodeWithText("Connect to a network to edit its ranges").assertIsDisplayed()
        rule.onNodeWithTag("addRange").assertIsNotEnabled()
    }
    @Test fun masterSwitchOffDisablesButShowsTheRows() {
        show(enabled = false)
        rule.onNode(hasText("Probe for gateways", substring = true) and hasClickAction()).assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("At most 4 extra /24 networks (about 2 minutes)").assertIsDisplayed()
        rule.onNodeWithTag("addRange").assertIsNotEnabled()
        rule.onNode(hasText("Extended discovery", substring = true) and hasClickAction()).assertIsEnabled().performClick()
        rule.onNode(hasText("Probe for gateways", substring = true) and hasClickAction()).assertIsEnabled()
    }
    @Test fun stepperChangesTheCapAndMarksEntriesBeyondIt() {
        store.set(1, listOf("10.0.4.0/24", "10.0.5.0/24", "10.0.6.0/24").map { CidrParser.subnetOf(it)!! })
        show(cap = 2)
        rule.onAllNodesWithText("Beyond cap, will be skipped").assertCountEquals(1)
        rule.onNodeWithContentDescription("More ranges").performClick()
        rule.onNodeWithText("At most 3 extra /24 networks (about 1 minute)").assertIsDisplayed()
        rule.onAllNodesWithText("Beyond cap, will be skipped").assertCountEquals(0)
    }
}
