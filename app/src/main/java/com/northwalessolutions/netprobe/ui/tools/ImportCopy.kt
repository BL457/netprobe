package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.tools.NetworkScope

/**
 * Strings of installed and imported script tools (tools platform B4): the store's broken-entry reasons, the import flow and its
 * confirmation sheet, My tools and Remove. Templates live in ImportCopyTest. Sentence case, no disclaimers.
 */
object ImportCopy {
    // Why an installed tool is listed as broken (shown under its name in My tools)
    const val FILE_MISSING = "The script file is missing"
    const val FILE_CHANGED = "The script file has changed since it was imported"
    const val FILE_UNREADABLE = "The script file cannot be read"
    const val FILE_NOT_TEXT = "The script file is not valid UTF-8 text"
    const val NOT_IN_LIST = "The tool list does not know this script"
    const val LIST_UNREADABLE = "The tool list could not be read"
    const val KEY_MISMATCH = "The script no longer matches its id"
    fun cannotLoad(reason: String) = "Cannot load: ${reason.take(REASON_ECHO)}"

    // Saving and removing
    const val SAVE_FAILED = "The tool could not be saved"
    const val REMOVE_FAILED = "The tool could not be removed"
    fun imported(name: String, replaced: Boolean) = if (replaced) "Replaced ${name.take(NAME_ECHO)}" else "Imported ${name.take(NAME_ECHO)}"
    fun removed(name: String) = "Removed ${name.take(NAME_ECHO)}"

    private const val REASON_ECHO = 200
    private const val NAME_ECHO = 40

    fun scopeWord(s: NetworkScope): String = when (s) {
        NetworkScope.TARGET -> "the device only"
        NetworkScope.LAN -> "your network"
        NetworkScope.ANY -> "any address"
    }
}
