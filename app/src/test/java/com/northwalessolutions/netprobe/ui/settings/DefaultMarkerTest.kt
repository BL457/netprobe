package com.northwalessolutions.netprobe.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 3C spec 8.1 item 2. */
class DefaultMarkerTest {
    @Test fun `null at the default and the default with its unit otherwise`() {
        assertNull(DefaultMarker.text(128, 128, ""))
        assertEquals("Default 128", DefaultMarker.text(256, 128, ""))
        assertNull(DefaultMarker.text(600, 600, " ms"))
        assertEquals("Default 600 ms", DefaultMarker.text(900, 600, " ms"))
        assertEquals("Default 4", DefaultMarker.text(7, 4, ""))
    }
    @Test fun `the description names the control and the default value`() {
        assertEquals("Reset Sweep concurrency to 128", SettingsCopy.resetDescription("Sweep concurrency", 128, ""))
        assertEquals("Reset Port scan timeout to 600 ms", SettingsCopy.resetDescription("Port scan timeout", 600, " ms"))
    }
}
