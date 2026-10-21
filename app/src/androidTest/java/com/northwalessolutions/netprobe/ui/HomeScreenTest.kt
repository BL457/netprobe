package com.northwalessolutions.netprobe.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.MainActivity
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun radarIsPinnedAndChipsFollowTheHeroRule() {
        rule.onNodeWithTag("radarBlock").assertIsDisplayed()
        val hero = rule.onAllNodesWithTag("scanButton").fetchSemanticsNodes().isNotEmpty()
        if (hero) {   // first launch on this network: chips and search are hidden (D12)
            rule.onNodeWithTag("filterRow").assertDoesNotExist(); rule.onNodeWithTag("search").assertDoesNotExist()
        } else {
            rule.onNodeWithTag("radarRing").assertIsDisplayed()
            val chips = rule.onNodeWithTag("filterRow").onChildren()
            chips.filterToOne(hasText("All")).assertIsDisplayed(); chips.filterToOne(hasText("Interesting")).assertIsDisplayed()
            rule.onNodeWithTag("search").assertIsDisplayed()
        }
    }
    private fun onLan(): Boolean {
        val cm = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
    }
    // Two gated tests, one outcome each: exactly one runs.
    @Test fun scanTogglesToStopWhenOnWifi() {
        Assume.assumeTrue("needs Wi-Fi or Ethernet", onLan())
        rule.onNode(hasTestTag("scanButton") or hasTestTag("radarRing")).assertIsEnabled().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("radarStop").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("radarStop").performClick()   // stopped at once; the scan only touches the LAN
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("radarRing") or hasTestTag("scanButton")).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun scanIsDisabledWithAMessageWhenOffWifi() {
        Assume.assumeFalse("needs no Wi-Fi or Ethernet", onLan())
        rule.onNodeWithTag("scanButton").assertIsNotEnabled()
        rule.onAllNodesWithText("Connect to Wi-Fi or Ethernet to scan").assertCountEquals(1)
    }
    @Test fun theBottomBarShowsFourDestinationsWithDevicesSelected() {   // 3D spec 1.2: the real shell around the real Devices root
        listOf("devices", "scans", "tools", "settings").forEach { rule.onNodeWithTag("tab:$it").assertIsDisplayed() }
        rule.onNodeWithTag("tab:devices").assertIsSelected()
    }
}
