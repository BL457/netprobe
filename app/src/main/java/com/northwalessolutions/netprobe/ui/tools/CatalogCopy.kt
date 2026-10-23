package com.northwalessolutions.netprobe.ui.tools

/**
 * Strings of the tool catalog (spec section 11): reading `catalog/index.json`, the "Update tools from GitHub" action and its review sheet.
 * Templates live in CatalogCopyTest. Sentence case, no disclaimers.
 */
object CatalogCopy {
    // Why a line of the index was left out (shown in the Failed list)
    fun indexLineBad(label: String, reason: String) = "${label.take(LABEL_ECHO)}: $reason"
    fun indexDuplicate(id: String) = "${id.take(LABEL_ECHO)} is listed twice, the second line was ignored"
    const val INDEX_TOO_LONG = "The catalog lists more than 200 tools, the rest were ignored"
    const val INDEX_FIELDS = "The catalog line is missing a field or has the wrong kind of value"
    const val INDEX_ID = "The id is not valid"
    const val INDEX_FILE = "The file name is not the id plus .js"
    const val INDEX_NAME = "The name is missing or longer than 40 characters"
    const val INDEX_VERSION = "The version is not valid"
    const val INDEX_SHA = "The SHA-256 is not 64 hex digits"
    const val INDEX_NETWORK = "The network is not target, lan or any"
    const val INDEX_DESCRIPTION = "The description is longer than 200 characters"
    const val INDEX_BROADCAST = "Only a tool with the network set to lan can declare broadcast"

    private const val LABEL_ECHO = 40
}
