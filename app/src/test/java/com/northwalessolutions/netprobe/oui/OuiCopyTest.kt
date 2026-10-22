package com.northwalessolutions.netprobe.oui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OuiCopyTest {
    private val templates = listOf(
        "Vendor database", "Update vendor database", "Stop", "Not downloaded", "Private address",
        "Names the maker of a device from its MAC address\\. Downloaded from the IEEE registry on request, about 5 MB, kept on the phone\\.",
        "Updated [0-9]{1,2} [A-Za-z]{3} [0-9]{4}, [0-9]+ vendors", "Downloading vendor database, [1-3] of 3",
        "Vendor database updated: [0-9]+ vendors", "MAC prefix: .+", "Vendor database update stopped", "MAC address is randomised",
        "A vendor file was larger than 8 MB", "Could not read the vendor file", "Could not save the vendor database", "The vendor files held no entries",
    ).map { Regex(it) }

    @Test fun `every vendor database string matches a template and has no em-dash`() {
        val all = listOf(OuiCopy.TITLE, OuiCopy.CAPTION, OuiCopy.NOT_DOWNLOADED, OuiCopy.BUTTON, OuiCopy.STOP, OuiCopy.CANCELLED, OuiCopy.TOO_LARGE, OuiCopy.UNREADABLE,
            OuiCopy.NOT_SAVED, OuiCopy.EMPTY, OuiCopy.PRIVATE_ADDRESS, OuiCopy.PRIVATE_REASON, OuiCopy.status("22 Oct 2026", 51234), OuiCopy.downloading(2, 3),
            OuiCopy.updated(51234), OuiCopy.macPrefix("Espressif Inc."))
        for (s in all) { assertTrue("not in the table: '$s'", templates.any { it.matches(s) }); assertFalse(s.contains(Char(0x2014))) }
    }
}
