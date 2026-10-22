package com.northwalessolutions.netprobe.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.ui.home.HostRow
import com.northwalessolutions.netprobe.ui.home.HostRowItem
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Long-press copy menus on a Devices row (Director ruling R4). Compiled with the suite, run on the phone. */
@RunWith(AndroidJUnit4::class)
class CopyMenuContentTest {
    @get:Rule val rule = createComposeRule()
    private fun row(mac: String?) = HostRow(1, "10.0.3.59", "CHNAS", "Synology DS220+", DeviceType.NAS, 3, HostStatus.UNMARKED, false, false, mac)

    @Test fun longPressShowsTheFourItemsWhenTheDeviceHasAMac() {
        val picked = mutableListOf<CopyKind>(); var taps = 0
        rule.setContent { NetProbeTheme { HostRowItem(row("00:11:32:9a:1f:e4"), own = false, ownModel = "", onClick = { taps++ }, onCopy = { picked += it }) } }
        rule.onNodeWithTag("hostRow").performTouchInput { longClick() }
        listOf("IP", "MAC", "NAME", "DETAILS").forEach { rule.onNodeWithTag("copy:$it").assertIsDisplayed() }
        rule.onNodeWithTag("copy:MAC").performClick()
        assertEquals(listOf(CopyKind.MAC), picked); assertEquals(0, taps)
    }
    @Test fun copyMacIsHiddenWhenTheDeviceHasNoMac() {
        rule.setContent { NetProbeTheme { HostRowItem(row(null), own = false, ownModel = "", onClick = {}, onCopy = {}) } }
        rule.onNodeWithTag("hostRow").performTouchInput { longClick() }
        listOf("IP", "NAME", "DETAILS").forEach { rule.onNodeWithTag("copy:$it").assertIsDisplayed() }
        rule.onNodeWithTag("copy:MAC").assertDoesNotExist()
    }
    @Test fun aTapStillOpensTheDevice() {
        var taps = 0
        rule.setContent { NetProbeTheme { HostRowItem(row("aa:bb"), own = false, ownModel = "", onClick = { taps++ }, onCopy = {}) } }
        rule.onNodeWithTag("hostRow").performClick()
        assertEquals(1, taps)
    }
}
