package com.northwalessolutions.netprobe.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The About dialog's three lines moved with the dialog (3D spec 2 and 5); the wording is the Phase 3A table. */
class AboutInfoTest {
    @Test fun `the dialog has a version line, a build line and an Android line`() {
        val info = AboutInfo("0.3.0", 3, "c8a62e6", "2026-10-21 14:05", "14")
        assertEquals(listOf("Version 0.3.0 (3)", "Build c8a62e6, 2026-10-21 14:05", "Android 14"), info.lines)
    }
    @Test fun `the Settings strings are the documented ones`() {
        assertEquals("Settings", SettingsCopy.TITLE); assertEquals("About", SettingsCopy.ABOUT_SECTION); assertEquals("About", SettingsCopy.ABOUT_ROW)
    }
}
