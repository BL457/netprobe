package com.northwalessolutions.netprobe.ui

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3D spec 9.2 item 1 and Review Focus 5: the shell with fake destinations (no container, no database). Reduced motion keeps every switch instant. */
@RunWith(AndroidJUnit4::class)
class NavBarTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nav: NavHostController
    private var back: () -> Unit = {}

    @Composable private fun Root(name: String, extra: @Composable () -> Unit = {}) {
        var n by rememberSaveable { mutableIntStateOf(0) }
        Column(Modifier.fillMaxSize().testTag("fake:$name")) {
            Text("count:$name=$n", Modifier.testTag("count:$name"))
            Button(onClick = { n++ }, modifier = Modifier.testTag("inc:$name")) { Text("inc") }
            extra()
        }
    }
    private fun show(scansRunning: Int = 0) = rule.setContent { NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides true) {
        nav = rememberNavController()
        val owner = LocalOnBackPressedDispatcherOwner.current
        SideEffect { back = { owner!!.onBackPressedDispatcher.onBackPressed() } }
        NetProbeShell(nav, scansRunning, NavSlots(
            devices = { Root("devices") { Button(onClick = { nav.navigate(Routes.host(Tab.DEVICES, 3)) }, modifier = Modifier.testTag("openHost:devices")) { Text("open") } } },
            scans = { Root("scans") { Button(onClick = { nav.navigate(Routes.host(Tab.SCANS, 5)) }, modifier = Modifier.testTag("openHost:scans")) { Text("open") } } },
            tools = { Root("tools") }, settings = { Root("settings") },
            host = { tab, id, label -> Box(Modifier.fillMaxSize().testTag("fakeHost:${tab.root}:$id:$label")) },
            wordlists = { tab, label -> Box(Modifier.fillMaxSize().testTag("fakeWordlists:${tab.root}:$label")) }))
    } } }
    private fun tap(tab: Tab) { rule.onNodeWithTag("tab:${tab.root}").performClick(); rule.waitForIdle() }
    private fun pressBack() { rule.runOnUiThread { back() }; rule.waitForIdle() }

    @Test fun fourItemsInBarOrderWithLabelsAndDevicesSelected() {
        show()
        val lefts = Tab.entries.map { rule.onNodeWithTag("tab:${it.root}").assertIsDisplayed().getBoundsInRoot().left }
        assertEquals(lefts.sorted(), lefts)
        listOf("Devices", "Scans", "Tools", "Settings").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithTag("tab:devices").assertIsSelected(); rule.onNodeWithTag("tab:scans").assertIsNotSelected()
    }
    @Test fun tappingEachDestinationSelectsItAndRestoresThatRootsState() {
        show()
        tap(Tab.SETTINGS); rule.onNodeWithTag("tab:settings").assertIsSelected()
        rule.onNodeWithTag("inc:settings").performClick(); rule.onNodeWithTag("inc:settings").performClick()
        tap(Tab.DEVICES); rule.onNodeWithTag("fake:devices").assertExists()
        tap(Tab.SETTINGS)
        rule.onNodeWithTag("count:settings").assertTextEquals("count:settings=2")
        tap(Tab.TOOLS); rule.onNodeWithTag("count:tools").assertTextEquals("count:tools=0")
    }
    @Test fun devicesThenScansOpenAHostBackToScansThenBackToDevices() {
        show()
        tap(Tab.SCANS)
        rule.onNodeWithTag("openHost:scans").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("fakeHost:scans:5:Back to Scans").assertExists()
        rule.onNodeWithTag("navBar").assertDoesNotExist()
        pressBack()
        rule.onNodeWithTag("fake:scans").assertExists(); rule.onNodeWithTag("navBar").assertExists()
        pressBack()
        rule.onNodeWithTag("fake:devices").assertExists(); rule.onNodeWithTag("tab:devices").assertIsSelected()
    }
    @Test fun backFromANonStartRootLandsOnDevices() {   // Review Focus 5
        show()
        tap(Tab.TOOLS); pressBack()
        rule.onNodeWithTag("fake:devices").assertExists()
        tap(Tab.SETTINGS); pressBack()
        rule.onNodeWithTag("fake:devices").assertExists()
    }
    @Test fun theBarIsAbsentOnHostAndWordlistsRoutesAndWordlistsNamesWhereBackGoes() {
        show()
        rule.onNodeWithTag("openHost:devices").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("fakeHost:devices:3:Back to Devices").assertExists(); rule.onNodeWithTag("navBar").assertDoesNotExist()
        rule.runOnUiThread { nav.navigate(Routes.wordlists(Tab.DEVICES)) }; rule.waitForIdle()
        rule.onNodeWithTag("fakeWordlists:devices:Back to device").assertExists(); rule.onNodeWithTag("navBar").assertDoesNotExist()
        pressBack(); pressBack()
        tap(Tab.TOOLS)
        rule.runOnUiThread { nav.navigate(Routes.wordlists(Tab.TOOLS)) }; rule.waitForIdle()
        rule.onNodeWithTag("fakeWordlists:tools:Back to Tools").assertExists(); rule.onNodeWithTag("navBar").assertDoesNotExist()
    }
    @Test fun rapidTabTapsLeaveOneDevicesEntryAndAtMostOneOtherDestination() {   // Review Focus 5
        show()
        listOf(Tab.SCANS, Tab.TOOLS, Tab.DEVICES, Tab.SCANS, Tab.SETTINGS, Tab.DEVICES, Tab.TOOLS, Tab.TOOLS, Tab.SETTINGS).forEach {
            rule.onNodeWithTag("tab:${it.root}").performClick()
        }
        rule.waitForIdle()
        val routes = nav.currentBackStack.value.mapNotNull { it.destination.route }
        val roots = routes.filter { Routes.isRoot(it) }
        assertEquals("exactly one devices root: $routes", 1, roots.count { it == "devices" })
        assertTrue("at most one other destination root: $routes", roots.size <= 2)
        assertEquals("settings", roots.last())
    }
}
