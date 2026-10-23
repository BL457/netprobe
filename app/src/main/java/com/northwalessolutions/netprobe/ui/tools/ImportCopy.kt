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

    // Why an import stops (all found problems are listed together)
    const val UNEXPECTED = "Something went wrong while reading the tool"
    const val FILE_UNOPENED = "The file could not be opened"
    const val TOO_LARGE = "The file is larger than 1 MB"
    const val EMPTY = "The file is empty"
    const val URL_BAD = "That is not a web address"
    const val URL_NOT_HTTPS = "The address must start with https:// and name a public host"
    const val FETCH_REFUSED = "The address, or a redirect, is not an https address on a public host"
    const val FETCH_REDIRECTS = "Too many redirects"
    const val FETCH_STATUS = "The server did not return the file"
    const val FETCH_CONNECTION = "Could not reach the server"
    const val FETCH_TIMEOUT = "The server took longer than 20 seconds"
    fun downgrade(installed: String, candidate: String) = "Version ${candidate.take(VERSION_ECHO)} is older than the installed version ${installed.take(VERSION_ECHO)}, so it was not imported"
    const val STALE = "The installed tool changed while you were reviewing, review it again"
    const val WIDENING_NEEDS_YES = "Confirm the wider network access to replace the tool"

    // Settings > Tools
    const val IMPORT_ROW = "Import tool"
    const val IMPORT_SUPPORT = "From a file or a web address"

    // Import dialogs
    const val IMPORT_TITLE = "Import tool"
    const val FROM_FILE = "From file"
    const val FROM_URL = "From a web address"
    const val URL_LABEL = "https:// address"
    const val URL_FETCH = "Fetch"
    const val CANCEL = "Cancel"
    const val CLOSE = "Close"
    const val WORKING_FILE = "Reading the file"
    const val WORKING_URL = "Fetching the file"
    const val FAILED_TITLE = "This tool cannot be imported"
    fun errorsCount(count: Int) = if (count == 1) "1 problem" else "$count problems"

    // Confirmation sheet
    fun reviewTitle(name: String, version: String) = "${name.take(NAME_ECHO)} ${version.take(VERSION_ECHO)}"
    const val REVIEW_NEW = "Review before you import"
    const val ROW_AUTHOR = "Author"
    fun authorUnverified(author: String?) = if (author.isNullOrBlank()) "Not given (unverified)" else "${author.take(NAME_ECHO)} (unverified)"
    const val ROW_NEEDS = "Needs"
    const val ROW_NETWORK = "Network"
    const val ROW_INPUTS = "Inputs"
    const val ROW_SHA = "SHA-256"
    const val NO_INPUTS = "None"
    const val CALLOUT_ANY = "This tool can contact any address"
    const val CALLOUT_LAN = "This tool can contact addresses on your network"
    fun inputLine(label: String, kind: String, required: Boolean) = if (required) "$label, $kind" else "$label, $kind, optional"
    const val VIEW_SOURCE = "View source"
    const val HIDE_SOURCE = "Hide source"
    fun sourceSize(chars: Int, lines: Int) = "$lines lines, $chars characters"
    const val IMPORT_BUTTON = "Import"
    const val REPLACE_BUTTON = "Replace"
    const val NOTHING_RUNS = "Importing only reads the description. The script runs when you run the tool."

    // Replace diff
    fun replaces(name: String, installed: String, candidate: String) = "Replaces ${name.take(NAME_ECHO)} ${installed.take(VERSION_ECHO)} with ${candidate.take(VERSION_ECHO)}"
    const val SAME_SCRIPT = "The script is identical to the installed one"
    fun scopeChange(from: String, to: String) = "Network: from $from to $to"
    const val SCOPE_SAME = "Network: unchanged"
    fun shaChange(from: String, to: String) = "SHA-256 from ${from.take(SHA_CHARS)} to ${to.take(SHA_CHARS)}"
    fun inputsAdded(lines: List<String>) = "New inputs: ${lines.joinToString(", ")}"
    fun inputsRemoved(lines: List<String>) = "Removed inputs: ${lines.joinToString(", ")}"
    fun inputsChanged(lines: List<String>) = "Changed inputs: ${lines.joinToString(", ")}"
    const val INPUTS_UNKNOWN = "The installed tool is broken, so its inputs cannot be compared"
    fun widened(from: String, to: String) = "This version reaches further than the installed one: from $from to $to"
    fun widenedYes(to: String) = "I allow it to contact $to"

    // Saving and removing
    const val SAVE_FAILED = "The tool could not be saved"
    const val REMOVE_FAILED = "The tool could not be removed"
    fun imported(name: String, replaced: Boolean) = if (replaced) "Replaced ${name.take(NAME_ECHO)}" else "Imported ${name.take(NAME_ECHO)}"
    fun removed(name: String) = "Removed ${name.take(NAME_ECHO)}"

    private const val REASON_ECHO = 200
    private const val NAME_ECHO = 40
    private const val VERSION_ECHO = 20
    private const val SHA_CHARS = 64

    fun scopeWord(s: NetworkScope): String = when (s) {
        NetworkScope.TARGET -> "the device only"
        NetworkScope.LAN -> "your network"
        NetworkScope.ANY -> "any address"
    }
}
