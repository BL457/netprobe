package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import org.junit.Assert.*
import org.junit.Test

/** Every ToolsUiCopy string against its template, with no em-dash and no disclaimer. */
class ToolsUiCopyTest {
    private val templates = listOf(
        // grid
        "More", "Import tool", "Any address", "Mine", "Web|Network|Identity|Examples|My tools", ".+, .+(, Any address)?(, Mine)?", "Actions for .+",
        "About", "Remove", "Copy source", "Source copied", "Close", "Version", "Author", "Needs", "Network", "Inputs",
        "A device and a port", "A device", "Nothing, it runs on its own",
        "Contacts only the device you run it on", "Contacts addresses on your network", "Can contact any address",
    ).map { Regex(it) }

    private fun inTable(s: String) { assertTrue("not in the table: '$s'", templates.any { it.matches(s) }); assertFalse("em-dash in '$s'", s.contains(0x2014.toChar())) }

    @Test fun `grid strings are in the table`() {
        listOf(ToolsUiCopy.MORE, ToolsUiCopy.IMPORT_TOOL, ToolsUiCopy.BADGE_ANY, ToolsUiCopy.BADGE_MINE, ToolsUiCopy.ABOUT, ToolsUiCopy.REMOVE, ToolsUiCopy.COPY_SOURCE,
            ToolsUiCopy.SOURCE_COPIED, ToolsUiCopy.CLOSE, ToolsUiCopy.ABOUT_VERSION, ToolsUiCopy.ABOUT_AUTHOR, ToolsUiCopy.ABOUT_NEEDS, ToolsUiCopy.ABOUT_NETWORK,
            ToolsUiCopy.ABOUT_INPUTS, ToolsUiCopy.menuDescription("SMB session"), ToolsUiCopy.tileDescription("SMB session", "Checks signing", any = true, mine = true),
            ToolsUiCopy.tileDescription("SMB session", "Checks signing", any = false, mine = false), ToolsUiCopy.tileDescription("SMB session", "", any = false, mine = false).let { "$it, x" })
            .forEach(::inTable)
        ToolCategory.entries.forEach { inTable(ToolsUiCopy.category(it)) }
        TargetKind.entries.forEach { inTable(ToolsUiCopy.needs(it)) }
        NetworkScope.entries.forEach { inTable(ToolsUiCopy.scope(it)) }
    }
    @Test fun `helpers read as written`() {
        assertEquals("Actions for SNMP walk", ToolsUiCopy.menuDescription("SNMP walk"))
        assertEquals("Scan, Finds hosts, Any address, Mine", ToolsUiCopy.tileDescription("Scan", "Finds hosts", any = true, mine = true))
        assertEquals("Scan, Finds hosts", ToolsUiCopy.tileDescription("Scan", "Finds hosts", any = false, mine = false))
        assertEquals("Scan", ToolsUiCopy.tileDescription("Scan", "", any = false, mine = false))
        assertEquals(listOf("Web", "Network", "Identity", "Examples", "My tools"), ToolCategory.entries.map { ToolsUiCopy.category(it) })
    }
}
