package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class RunSettingsTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private val env16 = NetworkEnv(ip("172.20.9.40"), 16, ip("172.20.0.1"), emptyList(), null, null, Transport.WIFI)
    private val manual = listOf(CidrParser.subnetOf("10.0.4.0/24")!!)

    @Test fun `of reads Aggressive scan, the limit and the manual list once`() {
        assertEquals(RunSettings(aggressive = true, maxPrefix = 20, manual = manual), RunSettings.of(SettingsState(extEnabled = true, maxPrefix = 20), manual))
    }
    @Test fun `bindOutside holds with Aggressive scan on or a manual range, never with neither`() {
        assertTrue(RunSettings(true, 22, emptyList()).bindOutside)
        assertTrue(RunSettings(false, 22, manual).bindOutside)
        assertFalse(RunSettings(false, 22, emptyList()).bindOutside)
    }
    @Test fun `home follows HomeRange`() {
        assertEquals("172.20.9.0/24", RunSettings(false, 22, emptyList()).home(env16).toString())
        assertEquals("172.20.8.0/22", RunSettings(true, 22, emptyList()).home(env16).toString())
        assertTrue(RunSettings(true, 22, emptyList()).homeRange(env16).clamped)
    }
    @Test fun `rows group under the aggressive home range`() {
        val h = TestHosts.host(ip = "172.20.10.7")
        assertEquals("172.20.8.0/22" to false, RowRules.row(h, env16, 22, 0, false, aggressive = true).let { it.subnet to it.offSubnet })
        assertEquals("172.20.10.0/24" to true, RowRules.row(h, env16, 22, 0, false, aggressive = false).let { it.subnet to it.offSubnet })
    }
    @Test fun `effective prefers the snapshot and falls back to the live settings`() {
        val snap = RunSettings(false, 22, emptyList())
        assertSame(snap, RunSettings.effective(snap, SettingsState(extEnabled = true, maxPrefix = 20), manual))
        assertEquals(RunSettings(true, 20, manual), RunSettings.effective(null, SettingsState(extEnabled = true, maxPrefix = 20), manual))
    }
}
