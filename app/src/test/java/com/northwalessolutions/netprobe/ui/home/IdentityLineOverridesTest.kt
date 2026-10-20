package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.ui.theme.EdgeColors
import com.northwalessolutions.netprobe.ui.theme.edgeColor
import org.junit.Assert.assertEquals
import org.junit.Test

/** spec 9.1 tests 22 (gateway edge) and 23, and spec 4.2 (search, own phone). */
class IdentityLineOverridesTest {
    @Test fun `identity line shows the overrides`() {
        val h = TestHosts.host(type = DeviceType.SERVER, vendor = "QNAP", model = "TS-453")
        assertEquals("Synology DS220+", IdentityLine.of(h.copy(userVendor = "Synology", userModel = "DS220+")))
        assertEquals("NAS", IdentityLine.of(TestHosts.host().copy(userType = DeviceType.NAS)))
        assertEquals("Linux NAS", IdentityLine.of(TestHosts.host(os = "Linux").copy(userType = DeviceType.NAS)))
    }
    @Test fun `the row type follows the override and a gateway stays teal whatever type is set`() {
        assertEquals(DeviceType.NAS, RowRules.row(TestHosts.host(type = DeviceType.SERVER).copy(userType = DeviceType.NAS), TestEnvs.home, 24, 0, false).type)
        for (t in DeviceType.entries) {
            val r = RowRules.row(TestHosts.host(ip = "192.168.1.1", gateway = true).copy(userType = t), TestEnvs.home, 24, 0, false)
            assertEquals(t, r.type); assertEquals(EdgeColors.router, r.type.edgeColor(r.isGateway))
        }
    }
    @Test fun `search finds a hand-entered model through the identity line`() {
        val row = RowRules.row(TestHosts.host().copy(userModel = "DS220+"), TestEnvs.home, 24, 0, false)
        assertEquals(listOf(row), HomeFilter.apply(listOf(row), StatusFilter.ALL, "ds220"))
    }
    @Test fun `the own phone keeps its model line unless a model is set`() {
        val own = TestHosts.host(ip = "192.168.1.50")                                     // TestEnvs.home's own address
        assertEquals(RowRules.OWN_LINE, RowRules.row(own, TestEnvs.home, 24, 0, false).identityLine)
        assertEquals(DeviceType.PHONE, RowRules.row(own, TestEnvs.home, 24, 0, false).type)
        val set = RowRules.row(own.copy(userModel = "Pixel 7 Pro (work)"), TestEnvs.home, 24, 0, false)
        assertEquals("Pixel 7 Pro (work)", set.identityLine)
    }
}
