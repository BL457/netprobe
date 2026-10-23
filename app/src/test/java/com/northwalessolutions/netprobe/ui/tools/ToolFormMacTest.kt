package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A host tool's own "mac" input follows the chosen device's MAC (spec section 11) but never overwrites what the user typed. */
class ToolFormMacTest {
    private val d = UiToolFakes.descriptor("user:wake", "Wake", ToolCategory.NETWORK, TargetKind.HOST, listOf(InputSpec.Text("mac", "MAC address"), InputSpec.Number("repeat", "Repeat", default = 3)), NetworkScope.LAN)

    @Test fun `a device screen start fills the mac from the device`() {
        val f = ToolFormRules.initial(d, 7, "192.168.1.20", null, mac = "aa:bb:cc:00:11:22")
        assertEquals("aa:bb:cc:00:11:22", f.values["mac"]); assertEquals("aa:bb:cc:00:11:22", f.autoMac)
        assertEquals("3", f.values["repeat"])
    }

    @Test fun `the device's mac beats a remembered one, and an unknown mac keeps the remembered text`() {
        assertEquals("aa:bb:cc:00:11:22", ToolFormRules.initial(d, 7, "192.168.1.20", null, remembered = mapOf("mac" to "11:22:33:44:55:66"), mac = "aa:bb:cc:00:11:22").values["mac"])
        assertEquals("11:22:33:44:55:66", ToolFormRules.initial(d, 7, "192.168.1.20", null, remembered = mapOf("mac" to "11:22:33:44:55:66"), mac = "").values["mac"])
        assertEquals("", ToolFormRules.initial(d).values["mac"])
    }

    @Test fun `choosing a device fills an empty mac and then follows the next device`() {
        var f = ToolFormRules.initial(d)
        f = ToolFormRules.withDevice(f, 1, "192.168.1.20", "aa:bb:cc:00:11:22")
        assertEquals("aa:bb:cc:00:11:22", f.values["mac"])
        f = ToolFormRules.withDevice(f, 2, "192.168.1.21", "aa:bb:cc:00:11:33")
        assertEquals("aa:bb:cc:00:11:33", f.values["mac"])
        f = ToolFormRules.withDevice(f, 3, "192.168.1.22", "")
        assertEquals("a device with no known mac clears what the form filled", "", f.values["mac"])
    }

    @Test fun `a mac the user typed is kept when the device changes`() {
        var f = ToolFormRules.initial(d)
        f = ToolFormRules.withDevice(f, 1, "192.168.1.20", "aa:bb:cc:00:11:22")
        f = ToolFormRules.withInput(f, "mac", "de:ad:be:ef:00:01")
        f = ToolFormRules.withDevice(f, 2, "192.168.1.21", "aa:bb:cc:00:11:33")
        assertEquals("de:ad:be:ef:00:01", f.values["mac"])
    }

    @Test fun `a tool without a mac input gains no value`() {
        val plain = UiToolFakes.descriptor("user:plain", "Plain", ToolCategory.NETWORK, TargetKind.HOST)
        val f = ToolFormRules.withDevice(ToolFormRules.initial(plain), 1, "192.168.1.20", "aa:bb:cc:00:11:22")
        assertTrue(f.values.isEmpty())
    }

    @Test fun `the device picker rows carry the normalised mac`() {
        val rows = DevicePick.rows(listOf(TestHosts.host("192.168.1.20", mac = "AA:BB:CC:00:11:22"), TestHosts.host("192.168.1.21", mac = null), TestHosts.host("192.168.1.22", mac = "junk")))
        assertEquals(listOf("aa:bb:cc:00:11:22", "", ""), rows.map { it.mac })
    }
}
