package com.northwalessolutions.netprobe.ui

/** Navigation strings (3D spec 6, with the Director's "Scans" label). Templates live in NavCopyTableTest. */
object NavCopy {
    const val DEVICES = "Devices"
    const val SCANS = "Scans"
    const val TOOLS = "Tools"
    const val SETTINGS = "Settings"
    /** Wordlists opened from a Device screen. */
    const val BACK_TO_DEVICE = "Back to device"
    private const val BADGE_CAP = 9

    fun label(tab: Tab): String = when (tab) {
        Tab.DEVICES -> DEVICES
        Tab.SCANS -> SCANS
        Tab.TOOLS -> TOOLS
        Tab.SETTINGS -> SETTINGS
    }
    fun scansBadge(n: Int) = "Scans, $n running"
    /** The count on the Scans icon: 1 to 9, then "9+" (the Running block holds at most eight rows, so the cap is a safety net). */
    fun badgeText(n: Int): String = if (n > BADGE_CAP) "$BADGE_CAP+" else "$n"
    /** The back row names where Back goes (D18), replacing "Back to Home". */
    fun backTo(tab: Tab) = "Back to ${label(tab)}"
}

/** Which back row a pushed screen shows. */
object NavLabels {
    fun host(tab: Tab): String = NavCopy.backTo(tab)
    /** Wordlists opened from a Device screen says "Back to device"; from a root it names that destination. */
    fun wordlists(previousRoute: String?, tab: Tab): String = if (Routes.isHost(previousRoute)) NavCopy.BACK_TO_DEVICE else NavCopy.backTo(tab)
}
