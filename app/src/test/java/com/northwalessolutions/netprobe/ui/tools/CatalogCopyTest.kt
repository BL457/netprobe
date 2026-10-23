package com.northwalessolutions.netprobe.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every CatalogCopy string against its template, with no em-dash, no disclaimer and no unbounded echo of catalog text. */
class CatalogCopyTest {
    private val templates = listOf(
        // the action and its states
        "Update tools from GitHub", "Get new and updated tools from the NetProbe catalog", "Tools from GitHub", "Reading the catalog", "Checking tool \\d+ of \\d+", "Installing",
        "Could not update tools", "Try again", "The catalog could not be read", "The script does not match the catalog, try again later", "The script's id is not the one the catalog lists",
        // the review sheet
        "(New|Updated|Failed) \\(\\d+\\)", ".{1,40} .{1,20}", ".{1,40} .{1,20} to .{1,20}", "Replaces your copy", "Newly able to send broadcast packets on your network",
        "I allow the changes marked above", ".{1,40}: .{1,200}", "(1 tool is|\\d+ tools are) up to date", "Nothing new to install", "Install", "Needs a newer app \\(\\d+\\)", ".{1,40} .{1,20}: needs NetProbe (version code \\d+|.{1,20}) or newer",
        // after Install
        "Installed (1 tool|\\d+ tools)", "Installed \\d+ of \\d+ tools, \\d+ failed",
        // lines of the index left out
        "Line \\d+", "Catalog", "Listed twice, the second line was ignored", "The catalog lists more than 200 tools, the rest were ignored",
        "The catalog line is missing a field or has the wrong kind of value", "The id is not valid", "The file name is not the id plus [.]js",
        "The name is missing or longer than 40 characters", "The version is not valid", "The SHA-256 is not 64 hex digits", "The network is not target, lan or any",
        "The description is longer than 200 characters", "Only a tool with the network set to lan can declare broadcast", "The minimum app version is not valid",
    ).map { Regex(it) }

    private fun all(): List<String> = listOf(
        CatalogCopy.ACTION, CatalogCopy.ACTION_SUPPORT, CatalogCopy.TITLE, CatalogCopy.READING_INDEX, CatalogCopy.checking(3, 12), CatalogCopy.INSTALLING, CatalogCopy.FAILED_TITLE,
        CatalogCopy.TRY_AGAIN, CatalogCopy.INDEX_UNREADABLE, CatalogCopy.SCRIPT_MISMATCH, CatalogCopy.ID_MISMATCH,
        CatalogCopy.newHeader(2), CatalogCopy.updatedHeader(1), CatalogCopy.failedHeader(3), CatalogCopy.nameVersion("Wake on LAN", "1.0"), CatalogCopy.nameVersion("n".repeat(100), "v".repeat(50)),
        CatalogCopy.updatedLine("Wake on LAN", "1.0", "1.1"), CatalogCopy.updatedLine("n".repeat(100), "v".repeat(50), "w".repeat(50)), CatalogCopy.REPLACES_COPY, CatalogCopy.BROADCAST_NEW,
        CatalogCopy.ACK, CatalogCopy.failureLine("Wake on LAN", "Could not reach the server"), CatalogCopy.failureLine("n".repeat(100), "r".repeat(500)), CatalogCopy.upToDate(1), CatalogCopy.upToDate(4),
        CatalogCopy.NOTHING_NEW, CatalogCopy.INSTALL, CatalogCopy.installed(1), CatalogCopy.installed(3), CatalogCopy.installedSome(2, 1),
        CatalogCopy.linePosition(3), CatalogCopy.CATALOG_LABEL, CatalogCopy.INDEX_DUPLICATE, CatalogCopy.INDEX_TOO_LONG, CatalogCopy.INDEX_FIELDS, CatalogCopy.INDEX_ID, CatalogCopy.INDEX_FILE,
        CatalogCopy.INDEX_NAME, CatalogCopy.INDEX_VERSION, CatalogCopy.INDEX_SHA, CatalogCopy.INDEX_NETWORK, CatalogCopy.INDEX_DESCRIPTION, CatalogCopy.INDEX_BROADCAST,
        CatalogCopy.needsNewerHeader(2), CatalogCopy.needsNewerLine("Web gallery", "1.0", "0.4.3", 12), CatalogCopy.needsNewerLine("Web gallery", "1.0", null, 12),
        CatalogCopy.needsNewerLine("n".repeat(100), "v".repeat(50), "w".repeat(50), 12), CatalogCopy.INDEX_MIN_VERSION,
    )

    @Test fun `every string is in the table and has no em-dash or disclaimer`() {
        val bad = all().filterNot { s -> templates.any { it.matches(s) } }
        assertTrue("not in the table: $bad", bad.isEmpty())
        all().forEach { s ->
            assertFalse("em-dash in '$s'", s.contains(0x2014.toChar()))
            assertFalse("disclaimer in '$s'", s.contains("please", ignoreCase = true) || s.contains("sorry", ignoreCase = true) || s.contains("legal", ignoreCase = true))
        }
    }

    @Test fun `helpers read as written`() {
        assertEquals("Checking tool 3 of 12", CatalogCopy.checking(3, 12))
        assertEquals("Wake on LAN 1.0 to 1.1", CatalogCopy.updatedLine("Wake on LAN", "1.0", "1.1"))
        assertEquals("New (2)", CatalogCopy.newHeader(2))
        assertEquals("1 tool is up to date", CatalogCopy.upToDate(1)); assertEquals("5 tools are up to date", CatalogCopy.upToDate(5))
        assertEquals("Installed 1 tool", CatalogCopy.installed(1)); assertEquals("Installed 2 of 3 tools, 1 failed", CatalogCopy.installedSome(2, 1))
    }

    @Test fun `echoed catalog text is cut`() {
        assertEquals(40 + 1 + 20, CatalogCopy.nameVersion("n".repeat(100), "v".repeat(50)).length)
        assertTrue(CatalogCopy.failureLine("n".repeat(100), "r".repeat(500)).length <= 40 + 2 + 200)
    }
}
