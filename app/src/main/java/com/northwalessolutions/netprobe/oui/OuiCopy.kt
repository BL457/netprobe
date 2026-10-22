package com.northwalessolutions.netprobe.oui

/** Every word the vendor database shows (Director ruling 2026-10-22 b, ruling 1). */
object OuiCopy {
    const val TITLE = "Vendor database"
    const val CAPTION = "Names the maker of a device from its MAC address. Downloaded from the IEEE registry on request, about 5 MB, kept on the phone."
    const val NOT_DOWNLOADED = "Not downloaded"
    const val BUTTON = "Update vendor database"
    const val STOP = "Stop"
    const val CANCELLED = "Vendor database update stopped"
    const val TOO_LARGE = "A vendor file was larger than 8 MB"
    const val UNREADABLE = "Could not read the vendor file"
    const val NOT_SAVED = "Could not save the vendor database"
    const val EMPTY = "The vendor files held no entries"
    /** The vendor text of a randomised (locally administered) MAC address. */
    const val PRIVATE_ADDRESS = "Private address"
    const val PRIVATE_REASON = "MAC address is randomised"

    fun status(date: String, count: Int) = "Updated $date, $count vendors"
    fun downloading(step: Int, of: Int) = "Downloading vendor database, $step of $of"
    fun updated(count: Int) = "Vendor database updated: $count vendors"
    fun macPrefix(vendor: String) = "MAC prefix: $vendor"
}
