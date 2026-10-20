package com.northwalessolutions.netprobe.ui.theme

import androidx.compose.ui.graphics.Color
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeColorTest {
    @Test fun `every type has a colour and the ten are pairwise distinct`() {
        val colours = DeviceType.entries.map { it.edgeColor() }
        assertEquals(DeviceType.entries.size, colours.toSet().size)
    }
    @Test fun `drawn and chosen values match the spec`() {
        assertEquals(Color(0xFF35D4C8), DeviceType.ROUTER.edgeColor()); assertEquals(Color(0xFF7FA7FF), DeviceType.NAS.edgeColor())
        assertEquals(Color(0xFFC98BFF), DeviceType.MEDIA.edgeColor()); assertEquals(Color(0xFFFFB86B), DeviceType.IOT.edgeColor())
        assertEquals(Color(0xFF8FA3B5), DeviceType.PHONE.edgeColor()); assertEquals(Color(0xFF9BE07A), DeviceType.COMPUTER.edgeColor())
        assertEquals(Color(0xFFF2A0D6), DeviceType.SERVER.edgeColor()); assertEquals(Color(0xFFFF8A8A), DeviceType.CAMERA.edgeColor())
        assertEquals(Color(0xFFD6C7A1), DeviceType.PRINTER.edgeColor()); assertEquals(Color(0xFF3B5368), DeviceType.UNKNOWN.edgeColor())
    }
    @Test fun `gateway overrides the type colour`() {
        for (t in DeviceType.entries) assertEquals(NetProbeColors.signal, t.edgeColor(isGateway = true))
    }
}
