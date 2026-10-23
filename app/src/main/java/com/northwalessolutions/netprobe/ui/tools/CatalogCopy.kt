package com.northwalessolutions.netprobe.ui.tools

/**
 * Strings of the tool catalog (spec section 11): reading `catalog/index.json`, the "Update tools from GitHub" action and its review sheet.
 * Templates live in CatalogCopyTest. Sentence case, no disclaimers.
 */
object CatalogCopy {
    // The action (Tools overflow and Settings > Tools)
    const val ACTION = "Update tools from GitHub"
    const val ACTION_SUPPORT = "Get new and updated tools from the NetProbe catalog"

    // Working and failed
    const val TITLE = "Tools from GitHub"
    const val READING_INDEX = "Reading the catalog"
    fun checking(done: Int, total: Int) = "Checking tool $done of $total"
    const val INSTALLING = "Installing"
    const val FAILED_TITLE = "Could not update tools"
    const val TRY_AGAIN = "Try again"
    const val INDEX_UNREADABLE = "The catalog could not be read"
    const val SCRIPT_MISMATCH = "The script does not match the catalog, try again later"
    const val ID_MISMATCH = "The script's id is not the one the catalog lists"

    // Review sheet
    fun newHeader(count: Int) = "New ($count)"
    fun updatedHeader(count: Int) = "Updated ($count)"
    fun failedHeader(count: Int) = "Failed ($count)"
    fun nameVersion(name: String, version: String) = "${name.take(NAME_ECHO)} ${version.take(VERSION_ECHO)}"
    fun updatedLine(name: String, from: String, to: String) = "${name.take(NAME_ECHO)} ${from.take(VERSION_ECHO)} to ${to.take(VERSION_ECHO)}"
    const val REPLACES_COPY = "Replaces your copy"
    const val BROADCAST_NEW = "Newly able to send broadcast packets on your network"
    const val ACK = "I allow the changes marked above"
    fun failureLine(name: String, reason: String) = "${name.take(NAME_ECHO)}: ${reason.take(REASON_ECHO)}"
    fun upToDate(count: Int) = if (count == 1) "1 tool is up to date" else "$count tools are up to date"
    const val NOTHING_NEW = "Nothing new to install"
    const val INSTALL = "Install"
    fun needsNewerHeader(count: Int) = "Needs a newer app ($count)"
    /** One tool the installed app is too old for: the app version when the index names it, else the version code. Never fetched or installed. */
    fun needsNewerLine(name: String, version: String, minName: String?, minCode: Int) =
        "${name.take(NAME_ECHO)} ${version.take(VERSION_ECHO)}: needs NetProbe ${minName?.take(VERSION_ECHO) ?: "version code $minCode"} or newer"

    // After Install
    fun installed(count: Int) = if (count == 1) "Installed 1 tool" else "Installed $count tools"
    fun installedSome(ok: Int, failed: Int) = "Installed $ok of ${ok + failed} tools, $failed failed"

    // Why a line of the index was left out (shown in the Failed list)
    fun linePosition(position: Int) = "Line $position"
    const val CATALOG_LABEL = "Catalog"
    const val INDEX_DUPLICATE = "Listed twice, the second line was ignored"
    const val INDEX_TOO_LONG = "The catalog lists more than 200 tools, the rest were ignored"
    const val INDEX_FIELDS = "The catalog line is missing a field or has the wrong kind of value"
    const val INDEX_ID = "The id is not valid"
    const val INDEX_FILE = "The file name is not the id plus .js"
    const val INDEX_NAME = "The name is missing or longer than 40 characters"
    const val INDEX_VERSION = "The version is not valid"
    const val INDEX_SHA = "The SHA-256 is not 64 hex digits"
    const val INDEX_NETWORK = "The network is not target, lan or any"
    const val INDEX_DESCRIPTION = "The description is longer than 200 characters"
    const val INDEX_MIN_VERSION = "The minimum app version is not valid"
    const val INDEX_BROADCAST = "Only a tool with the network set to lan can declare broadcast"

    private const val NAME_ECHO = 40
    private const val VERSION_ECHO = 20
    private const val REASON_ECHO = 200
}
