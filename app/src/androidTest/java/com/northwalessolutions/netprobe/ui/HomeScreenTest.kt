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
    @Test fun scanButtonAndFiltersArePresentAndPinned() {
        rule.onNodeWithTag("scanButton").assertIsDisplayed()
        // Scoped to the filter row: rows on a real LAN can carry "Interesting" status chips too.
        val chips = rule.onNodeWithTag("filterRow").onChildren()
        chips.filterToOne(hasText("All")).assertIsDisplayed(); chips.filterToOne(hasText("Interesting")).assertIsDisplayed()
        rule.onNodeWithTag("search").assertIsDisplayed()
    }
    private fun onLan(): Boolean {
        val cm = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
    }
    // Two gated tests, one outcome each: exactly one of them runs, and neither can pass by catching its own failure.
    @Test fun scanTogglesToStopWhenOnWifi() {
        Assume.assumeTrue("needs Wi-Fi or Ethernet", onLan())
        rule.onNodeWithTag("scanButton").assertIsEnabled().performClick()
        rule.onNodeWithText("Stop").assertIsDisplayed().performClick()   // stopped at once; the scan only touches the LAN
        rule.onNodeWithText("Scan").assertIsDisplayed()
    }
    @Test fun scanIsDisabledWithAMessageWhenOffWifi() {
        Assume.assumeFalse("needs no Wi-Fi or Ethernet", onLan())
        rule.onNodeWithTag("scanButton").assertIsNotEnabled()
        rule.onNodeWithText("Connect to Wi-Fi or Ethernet to scan").assertIsDisplayed()
    }
}
