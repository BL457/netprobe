package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.tools.ToolIcon

/**
 * The fixed icon set of spec section 6 as Material icon names, with no Compose in it so a plain unit test can pin it. [ToolIcons] turns
 * the same names into vectors. A name outside the set falls back to the terminal icon ([ToolIcon.parse]).
 */
object ToolIconMap {
    fun materialName(icon: ToolIcon): String = when (icon) {
        ToolIcon.WEB -> "Language"
        ToolIcon.SEARCH -> "Search"
        ToolIcon.LOCK -> "Lock"
        ToolIcon.FOLDER -> "FolderOpen"
        ToolIcon.SHARE -> "Share"
        ToolIcon.DNS -> "Dns"
        ToolIcon.TERMINAL -> "Terminal"
        ToolIcon.FINGERPRINT -> "Fingerprint"
        ToolIcon.NETWORK -> "Lan"
        ToolIcon.WIFI -> "Wifi"
        ToolIcon.INFO -> "Info"
        ToolIcon.BOLT -> "Bolt"
    }

    /** The Material name for a descriptor's icon name; null, blank and unknown names give the terminal icon's. */
    fun materialName(name: String?): String = materialName(ToolIcon.parse(name))
}
