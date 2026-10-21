package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.engine.discovery.ExtendedOptions
import com.northwalessolutions.netprobe.netenv.CidrParser
import org.junit.Assert.assertEquals
import org.junit.Test

/** 3C spec 8.1 item 6. */
class ExtendedOptionsOfTest {
    private val manual = listOf(CidrParser.subnetOf("10.0.4.0/24")!!)
    private val known = setOf("10.0.3.5")

    @Test fun `aggressive on with smart discovery off carries the manual list and evidence`() {
        val o = ExtendedOptionsOf.from(RunSettings(true, 22, manual), SettingsState(extEnabled = true, extMax = 6, extGatewayProbe = false), known)
        assertEquals(ExtendedOptions(enabled = true, maxRanges = 6, gatewayProbe = false, manual = manual, knownIps = known, evidence = true), o)
    }
    @Test fun `aggressive on with smart discovery on probes`() {
        val o = ExtendedOptionsOf.from(RunSettings(true, 22, emptyList()), SettingsState(extEnabled = true, extGatewayProbe = true), known)
        assertEquals(ExtendedOptions(enabled = true, maxRanges = 4, gatewayProbe = true, manual = emptyList(), knownIps = known, evidence = true), o)
    }
    @Test fun `aggressive off with a manual list sweeps only the list`() {
        val o = ExtendedOptionsOf.from(RunSettings(false, 22, manual), SettingsState(extEnabled = false, extMax = 6, extGatewayProbe = true), known)
        assertEquals(ExtendedOptions(enabled = true, maxRanges = 6, gatewayProbe = false, manual = manual, knownIps = known, evidence = false), o)
    }
    @Test fun `aggressive off with no manual ranges is ExtendedOptions()`() {
        assertEquals(ExtendedOptions(), ExtendedOptionsOf.from(RunSettings(false, 22, emptyList()), SettingsState(extGatewayProbe = true, extMax = 9), known))
    }
}
