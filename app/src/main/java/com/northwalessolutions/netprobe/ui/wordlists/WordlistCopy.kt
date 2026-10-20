package com.northwalessolutions.netprobe.ui.wordlists

/** Every Wordlists screen, import and Settings string (spec 9). Templates live in ToolCopyTableTest. */
object WordlistCopy {
    const val BACK = "Back"
    const val TITLE = "Wordlists"
    const val IMPORT = "Import wordlist"
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
    const val DEFAULT_NAME = "Imported list"
    const val SETTINGS_SECTION = "Tools"
    const val SETTINGS_ROW = "Wordlists"
    private fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"
    fun entriesBuiltIn(n: Int) = "${entries(n)}, built in"
    fun entriesImported(n: Int, date: String) = "${entries(n)}, imported $date"
    fun recoveredName(n: Int) = "Recovered list $n"
    fun deletePrompt(name: String) = "Delete $name?"
    fun deleteDescription(name: String) = "Delete $name"
    fun imported(name: String, n: Int, skipped: Int) = "Imported $name, ${entries(n)}${if (skipped > 0) " ($skipped skipped)" else ""}"
    fun settingsSupport(imported: Int) = "3 built in, $imported imported"
}
