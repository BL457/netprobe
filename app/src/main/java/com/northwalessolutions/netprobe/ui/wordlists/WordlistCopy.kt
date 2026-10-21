package com.northwalessolutions.netprobe.ui.wordlists

import java.util.Locale

/** Every Wordlists screen, import and Settings string (spec 9). Templates live in ToolCopyTableTest. */
object WordlistCopy {
    const val BACK = "Back"
    const val TITLE = "Wordlists"
    const val IMPORT = "Import file"
    const val IMPORTING = "Importing"
    const val BUILT_IN = "Built in"
    const val IMPORTED = "Imported"
    const val DELETE = "Delete"
    const val CANCEL = "Cancel"
    const val NO_IMPORTED = "No imported wordlists yet"
    const val TOO_LARGE = "That file is too large (limit 10 MB)"
    const val TOO_MANY = "That file has more than 200000 entries"
    const val EMPTY_FILE = "That file has no entries"
    const val UNREADABLE = "Could not read that file"
    const val LOADING = "Loading wordlists"
    const val DEFAULT_NAME = "Imported list"
    const val SETTINGS_SECTION = "Tools"
    const val SETTINGS_ROW = "Wordlists"
    // URL import (3C spec 5, 7.2)
    const val IMPORT_URL = "Import from URL"
    const val URL_LABEL = "URL"
    const val URL_PLACEHOLDER = "https://example.com/wordlist.txt"
    const val URL_IMPORT = "Import"
    const val ERR_URL = "Enter a web address starting with https:// or http://"
    const val ERR_URL_USER = "Remove the user name and password from the address"
    const val ERR_URL_LONG = "That address is too long"
    const val CANCELLED = "Import cancelled"
    const val FETCH_HOST = "Could not fetch: unknown host"
    const val FETCH_NO_ANSWER = "Could not fetch: no answer"
    const val FETCH_TLS = "Could not fetch: secure connection failed"
    const val FETCH_CONNECTION = "Could not fetch: connection failed"
    const val FETCH_INSECURE = "Could not fetch: redirected to an insecure address"
    const val FETCH_LOCAL = "Could not fetch: redirected to a local address"
    const val FETCH_REDIRECTS = "Could not fetch: too many redirects"
    private const val BYTES_PER_MB = 1_048_576.0
    private fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"
    fun entriesBuiltIn(n: Int) = "${entries(n)}, built in"
    fun entriesImported(n: Int, date: String) = "${entries(n)}, imported $date"
    fun recoveredName(n: Int) = "Recovered list $n"
    fun deletePrompt(name: String) = "Delete $name?"
    fun deleteDescription(name: String) = "Delete $name"
    fun imported(name: String, n: Int, skipped: Int) = "Imported $name, ${entries(n)}${if (skipped > 0) " ($skipped skipped)" else ""}"
    fun settingsSupport(imported: Int?) = if (imported == null) "3 built in" else "3 built in, $imported imported"
    /** One decimal place in MB of 1,048,576 bytes, always a full stop (spec 7.2). */
    private fun mb(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / BYTES_PER_MB)
    fun fetching(read: Long, total: Long?) = if (total == null) "Fetching ${mb(read)} MB" else "Fetching ${mb(read)} of ${mb(total)} MB"
    fun fetchStatus(code: Int) = "Could not fetch: the server answered $code"
    fun fetchNotText(type: String?) = if (type == null) "Could not fetch: not a text file" else "Could not fetch: not a text file ($type)"
}
