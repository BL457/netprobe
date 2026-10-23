package com.northwalessolutions.netprobe.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCopyTest {
    private val templates = listOf(
        "Update", "Later", "Stop", "Install", "Retry", "What's new", "Hide", "Downloading update", "Checking the download", "Update ready to install",
        "Allow NetProbe to install updates", "Android asks once before an app may install updates\\. Open settings, allow NetProbe, then come back and tap Update\\.",
        "Open settings", "Cancel", "Updates", "Check for updates automatically", "At most once a day, when NetProbe opens", "Check for updates",
        "Look for a newer version now", "Checking for updates", "NetProbe is up to date", "Could not read the update information", "Could not check for updates",
        "Could not download the update", "The update is larger than 64 MB", "The download was not the expected size", "The download did not match its checksum",
        "Could not save the update", "Update rejected: it was not signed with NetProbe's key", "No installer is available on this phone",
        "NetProbe .+ is available", "Release notes", "All versions on GitHub", "Got it", "What's new in .+", "Last checked (just now|[0-9]+ (minute|hour|day)s? ago)", "[0-9]{1,3}%",
    ).map { Regex(it) }

    @Test fun `every update string matches a template and has no em-dash`() {
        val all = listOf(UpdateCopy.UPDATE, UpdateCopy.LATER, UpdateCopy.STOP, UpdateCopy.INSTALL, UpdateCopy.RETRY, UpdateCopy.WHATS_NEW, UpdateCopy.HIDE_NOTES,
            UpdateCopy.DOWNLOADING, UpdateCopy.VERIFYING, UpdateCopy.READY, UpdateCopy.PERMISSION_TITLE, UpdateCopy.PERMISSION_BODY, UpdateCopy.PERMISSION_OPEN,
            UpdateCopy.PERMISSION_CANCEL, UpdateCopy.SETTINGS_SECTION, UpdateCopy.AUTO_LABEL, UpdateCopy.AUTO_SUPPORT, UpdateCopy.CHECK_ROW, UpdateCopy.CHECK_SUBTITLE,
            UpdateCopy.CHECKING, UpdateCopy.UP_TO_DATE, UpdateCopy.BAD_MANIFEST, UpdateCopy.CHECK_FAILED, UpdateCopy.FAIL_DOWNLOAD, UpdateCopy.FAIL_TOO_LARGE,
            UpdateCopy.FAIL_SIZE, UpdateCopy.FAIL_HASH, UpdateCopy.FAIL_SAVE, UpdateCopy.REJECTED, UpdateCopy.NO_INSTALLER,
            UpdateCopy.available("0.4.0"), UpdateCopy.RELEASE_NOTES_ROW, UpdateCopy.RELEASE_NOTES_SUBTITLE, UpdateCopy.GOT_IT, UpdateCopy.whatsNewTitle("0.3.3"), UpdateCopy.percent(42),
            UpdateCopy.lastChecked(0), UpdateCopy.lastChecked(5 * 60_000L), UpdateCopy.lastChecked(3 * 3_600_000L), UpdateCopy.lastChecked(2 * 86_400_000L)) + InstallFailure.entries.map { UpdateCopy.failure(it) }
        for (s in all) { assertTrue("not in the table: '$s'", templates.any { it.matches(s) }); assertFalse(s.contains(Char(0x2014))) }
    }

    @Test fun `last checked bands`() {
        assertEquals("Last checked just now", UpdateCopy.lastChecked(59_999L))
        assertEquals("Last checked 1 minute ago", UpdateCopy.lastChecked(60_000L))
        assertEquals("Last checked 59 minutes ago", UpdateCopy.lastChecked(59 * 60_000L + 59_000L))
        assertEquals("Last checked 1 hour ago", UpdateCopy.lastChecked(3_600_000L))
        assertEquals("Last checked 23 hours ago", UpdateCopy.lastChecked(23 * 3_600_000L + 59 * 60_000L))
        assertEquals("Last checked 1 day ago", UpdateCopy.lastChecked(86_400_000L))
        assertEquals("Last checked 3 days ago", UpdateCopy.lastChecked(3 * 86_400_000L))
    }

    @Test fun `releases url`() { assertEquals("https://github.com/BL457/netprobe/releases", UpdateConfig.RELEASES_URL) }

    @Test fun `first line skips blanks and trims`() {
        assertEquals("Fixes", UpdateCopy.firstLine("\n  Fixes  \nMore"))
        assertEquals("", UpdateCopy.firstLine(""))
        assertEquals("Changelog first", UpdateCopy.firstLine("- Changelog first\n- Second"))
    }
}
