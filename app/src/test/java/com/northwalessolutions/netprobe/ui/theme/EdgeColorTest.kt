package com.northwalessolutions.netprobe.ui.theme

import androidx.compose.ui.graphics.Color
import com.northwalessolutions.netprobe.data.AppSettings
import com.northwalessolutions.netprobe.data.FakePrefs
import com.northwalessolutions.netprobe.data.SettingsDefaults
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.data.TypeMarker
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.ui.settings.SettingsCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeColorTest {
    @Test fun `every type has a colour and the ten are pairwise distinct`() {
        val colours = DeviceType.entries.map { it.edgeColor() }
        assertEquals(DeviceType.entries.size, colours.toSet().size)
    }
    @Test fun `type to colour maps every DeviceType to the ruled palette`() {
        val expected = mapOf(
            DeviceType.ROUTER to 0xFF2DD4BF, DeviceType.NAS to 0xFF60A5FA, DeviceType.MEDIA to 0xFFC084FC, DeviceType.IOT to 0xFFFBBF24,
            DeviceType.PHONE to 0xFF7DD3FC, DeviceType.COMPUTER to 0xFFA3E635, DeviceType.SERVER to 0xFFFB7185, DeviceType.CAMERA to 0xFFEF4444,
            DeviceType.PRINTER to 0xFFD6D3D1, DeviceType.UNKNOWN to 0xFF475569)
        assertEquals(DeviceType.entries.toSet(), expected.keys)
        expected.forEach { (t, hex) -> assertEquals("$t", Color(hex), t.edgeColor()) }
    }
    @Test fun `type to icon maps every DeviceType to its own glyph`() {
        val expected = mapOf(
            DeviceType.ROUTER to "Filled.Router", DeviceType.NAS to "Filled.Storage", DeviceType.MEDIA to "Filled.Tv", DeviceType.IOT to "Filled.Lightbulb",
            DeviceType.PHONE to "Filled.Smartphone", DeviceType.COMPUTER to "Filled.Laptop", DeviceType.SERVER to "Filled.Dns",
            DeviceType.CAMERA to "Filled.Videocam", DeviceType.PRINTER to "Filled.Print", DeviceType.UNKNOWN to "Filled.HelpOutline")
        assertEquals(DeviceType.entries.toSet(), expected.keys)
        expected.forEach { (t, name) -> assertEquals("$t", name, t.markerIcon().name) }
        assertEquals(DeviceType.entries.size, DeviceType.entries.map { it.markerIcon().name }.toSet().size)
    }
    @Test fun `gateway overrides the type colour`() {
        for (t in DeviceType.entries) assertEquals(EdgeColors.router, t.edgeColor(isGateway = true))
    }
    @Test fun `the marker setting defaults to the icon, round-trips through the prefs and a reset restores the icon`() {
        assertEquals(TypeMarker.ICON, SettingsState().typeMarker)
        val prefs = FakePrefs(); AppSettings(prefs).update { it.copy(typeMarker = TypeMarker.STRIPE) }
        assertEquals("STRIPE", prefs.stored["typeMarker"])
        assertEquals(TypeMarker.STRIPE, AppSettings(prefs).state.value.typeMarker)
        assertTrue(SettingsDefaults.differs(SettingsState(typeMarker = TypeMarker.STRIPE)))
        assertEquals(TypeMarker.ICON, SettingsDefaults.reset(SettingsState(typeMarker = TypeMarker.STRIPE)).typeMarker)
    }
    @Test fun `an unknown stored marker falls back to the icon`() {
        val prefs = FakePrefs(); AppSettings(prefs).update { it.copy(typeMarker = TypeMarker.STRIPE) }
        prefs.stored["typeMarker"] = "GLOW"
        assertEquals(TypeMarker.ICON, AppSettings(prefs).state.value.typeMarker)
    }
    @Test fun `the Settings copy for the marker`() {
        val templates = listOf("Display", "Device type marker", "Stripe", "Icon").map { Regex(it) }
        listOf(SettingsCopy.DISPLAY_SECTION, SettingsCopy.TYPE_MARKER_LABEL, SettingsCopy.MARKER_STRIPE, SettingsCopy.MARKER_ICON).forEach { s ->
            assertTrue("not in the table: '$s'", templates.any { it.matches(s) }); assertFalse(s.contains(Char(0x2014)))
        }
    }
}
