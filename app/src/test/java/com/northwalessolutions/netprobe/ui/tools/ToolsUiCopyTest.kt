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
        // tool page
        "This tool is not installed", "Choose a device", "Search devices", "No devices match", "Use \\d+\\.\\d+\\.\\d+\\.\\d+", "Open ports", "Or type a port",
        "No open ports stored for this device", "\\d+( {2}.+)?", "That wordlist is gone, choose another", "Complete the form to run", "Add", "Remove .+",
        "\\d+ to \\d+", "\\d+ or (more|less)", "Results", "Previous runs", "No runs of this tool yet", "Log", "Hide log", "Show log, (1 line|\\d+ lines)",
        "No device|\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?", "Show results for .+", "Run .+\\?",
        ".+ can contact any address, not only the device you chose\\. It will run on this network now\\.", "Run", "Cancel",
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
    @Test fun `tool page strings are in the table`() {
        listOf(ToolsUiCopy.NOT_FOUND, ToolsUiCopy.DEVICE_PLACEHOLDER, ToolsUiCopy.DEVICE_SEARCH, ToolsUiCopy.NO_DEVICE_CHOSEN, ToolsUiCopy.NO_MATCHES, ToolsUiCopy.useAddress("8.8.8.8"),
            ToolsUiCopy.PORTS_OPEN, ToolsUiCopy.PORT_TYPED, ToolsUiCopy.NO_OPEN_PORTS, ToolsUiCopy.portChoice(22, "SSH"), ToolsUiCopy.portChoice(22, ""), ToolsUiCopy.WORDLIST_GONE,
            ToolsUiCopy.COMPLETE_FORM, ToolsUiCopy.LIST_ADD, ToolsUiCopy.listRemove("php"), ToolsUiCopy.range(1, 16)!!, ToolsUiCopy.range(1, null)!!, ToolsUiCopy.range(null, 9)!!,
            ToolsUiCopy.RESULTS, ToolsUiCopy.PREVIOUS_RUNS, ToolsUiCopy.NO_PREVIOUS_RUNS, ToolsUiCopy.LOG, ToolsUiCopy.logToggle(true, 3), ToolsUiCopy.logToggle(false, 1),
            ToolsUiCopy.logToggle(false, 20), ToolsUiCopy.runTitle(null, 0), ToolsUiCopy.runTitle("10.0.3.5", 0), ToolsUiCopy.runTitle("10.0.3.5", 443),
            ToolsUiCopy.runDescription("10.0.3.5:443"), ToolsUiCopy.confirmTitle("Scanner"), ToolsUiCopy.confirmBody("Scanner"), ToolsUiCopy.CONFIRM_RUN, ToolsUiCopy.CANCEL)
            .forEach(::inTable)
        assertNull(ToolsUiCopy.range(null, null))
        assertEquals("22  SSH", ToolsUiCopy.portChoice(22, "SSH")); assertEquals("22", ToolsUiCopy.portChoice(22, ""))
        assertEquals("Show log, 1 line", ToolsUiCopy.logToggle(false, 1)); assertEquals("Hide log", ToolsUiCopy.logToggle(true, 5))
        assertEquals("No device", ToolsUiCopy.runTitle(null, 80)); assertEquals("10.0.3.5", ToolsUiCopy.runTitle("10.0.3.5", 0)); assertEquals("10.0.3.5:443", ToolsUiCopy.runTitle("10.0.3.5", 443))
    }
    @Test fun `no page string is a disclaimer`() {
        val all = listOf(ToolsUiCopy.confirmTitle("X"), ToolsUiCopy.confirmBody("X"), ToolsUiCopy.COMPLETE_FORM, ToolsUiCopy.NOT_FOUND)
        all.forEach { s -> listOf("legal", "authoris", "permission", "responsib", "illegal").forEach { w -> assertFalse("$s mentions $w", s.lowercase().contains(w)) } }
    }
    @Test fun `helpers read as written`() {
        assertEquals("Actions for SNMP walk", ToolsUiCopy.menuDescription("SNMP walk"))
        assertEquals("Scan, Finds hosts, Any address, Mine", ToolsUiCopy.tileDescription("Scan", "Finds hosts", any = true, mine = true))
        assertEquals("Scan, Finds hosts", ToolsUiCopy.tileDescription("Scan", "Finds hosts", any = false, mine = false))
        assertEquals("Scan", ToolsUiCopy.tileDescription("Scan", "", any = false, mine = false))
        assertEquals(listOf("Web", "Network", "Identity", "Examples", "My tools"), ToolCategory.entries.map { ToolsUiCopy.category(it) })
    }
}
