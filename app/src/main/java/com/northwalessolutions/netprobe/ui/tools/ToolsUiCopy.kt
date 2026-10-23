package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolCategory

/**
 * Strings of the Tools grid, the tool page and its generated form (tools platform B3). Sentence case, active voice, no disclaimers.
 * Templates live in ToolsUiCopyTest. Run, Stop, Copy and the finding rows reuse ToolCopy; the root title and Wordlists are ToolsCopy.
 */
object ToolsUiCopy {
    // Grid
    const val MORE = "More"
    const val IMPORT_TOOL = "Import tool"
    const val BADGE_ANY = "Any address"
    const val BADGE_MINE = "Mine"
    fun category(c: ToolCategory): String = when (c) {
        ToolCategory.WEB -> "Web"
        ToolCategory.NETWORK -> "Network"
        ToolCategory.IDENTITY -> "Identity"
        ToolCategory.EXAMPLES -> "Examples"
        ToolCategory.MINE -> "My tools"
    }
    fun tileDescription(name: String, line: String, any: Boolean, mine: Boolean): String =
        listOfNotNull(name, line.ifEmpty { null }, BADGE_ANY.takeIf { any }, BADGE_MINE.takeIf { mine }).joinToString(", ")
    fun menuDescription(name: String) = "Actions for $name"

    // Tile menu and About
    const val ABOUT = "About"
    const val REMOVE = "Remove"
    const val COPY_SOURCE = "Copy source"
    const val SOURCE_COPIED = "Source copied"
    const val CLOSE = "Close"
    const val ABOUT_VERSION = "Version"
    const val ABOUT_AUTHOR = "Author"
    const val ABOUT_NEEDS = "Needs"
    const val ABOUT_NETWORK = "Network"
    const val ABOUT_INPUTS = "Inputs"
    fun needs(t: TargetKind): String = when (t) {
        TargetKind.PORT -> "A device and a port"
        TargetKind.HOST -> "A device"
        TargetKind.NONE -> "Nothing, it runs on its own"
    }
    fun scope(s: NetworkScope): String = when (s) {
        NetworkScope.TARGET -> "Contacts only the device you run it on"
        NetworkScope.LAN -> "Contacts addresses on your network"
        NetworkScope.ANY -> "Can contact any address"
    }

    // Tool page
    const val NOT_FOUND = "This tool is not installed"
    const val DEVICE_PLACEHOLDER = "Choose a device"
    const val DEVICE_SEARCH = "Search devices"
    const val NO_DEVICE_CHOSEN = "Choose a device"
    const val NO_MATCHES = "No devices match"
    fun useAddress(ip: String) = "Use $ip"
    const val PORTS_OPEN = "Open ports"
    const val PORT_TYPED = "Or type a port"
    const val NO_OPEN_PORTS = "No open ports stored for this device"
    fun portChoice(port: Int, service: String) = if (service.isEmpty()) "$port" else "$port  $service"
    const val WORDLIST_GONE = "That wordlist is gone, choose another"
    const val COMPLETE_FORM = "Complete the form to run"
    const val LIST_ADD = "Add"
    fun listRemove(item: String) = "Remove $item"
    fun range(min: Int?, max: Int?): String? = when {
        min != null && max != null -> "$min to $max"
        min != null -> "$min or more"
        max != null -> "$max or less"
        else -> null
    }

    // Run and results on the page
    const val RESULTS = "Results"
    const val PREVIOUS_RUNS = "Previous runs"
    const val NO_PREVIOUS_RUNS = "No runs of this tool yet"
    const val LOG = "Log"
    fun logToggle(expanded: Boolean, lines: Int) = if (expanded) "Hide log" else "Show log, ${if (lines == 1) "1 line" else "$lines lines"}"
    fun runTitle(ip: String?, port: Int): String = if (ip == null) ToolCopy.NO_DEVICE else if (port == 0) ip else "$ip:$port"
    fun runDescription(title: String) = "Show results for $title"

    // Confirmation for a tool that can contact any address (per run)
    fun confirmTitle(name: String) = "Run $name?"
    fun confirmBody(name: String) = "$name can contact any address, not only the device you chose. It will run on this network now."
    const val CONFIRM_RUN = "Run"
    const val CANCEL = "Cancel"
}
