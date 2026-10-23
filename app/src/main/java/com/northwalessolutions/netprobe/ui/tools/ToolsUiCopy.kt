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
}
