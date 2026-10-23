package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every ImportCopy string against its template, with no em-dash and no disclaimer. */
class ImportCopyTest {
    private val templates = listOf(
        // broken entries
        "The script file is missing", "The script file has changed since it was imported", "The script file cannot be read", "The script file is not valid UTF-8 text",
        "The tool list does not know this script", "The tool list could not be read", "The script no longer matches its id", "Cannot load: .{1,200}",
        // why an import stops
        "The file could not be opened", "The file is larger than 1 MB", "The file is empty", "That is not a web address", "The address must start with https:// and name a public host",
        "The address, or a redirect, is not an https address on a public host", "Too many redirects", "The server did not return the file", "Could not reach the server",
        "The server took longer than 20 seconds", "Version .{1,20} is older than the installed version .{1,20}, so it was not imported",
        "The installed tool changed while you were reviewing, review it again", "Confirm the wider network access to replace the tool", "Something went wrong while reading the tool",
        // settings and dialogs
        "Import tool", "From a file or a web address", "From file", "From a web address", "https:// address", "Fetch", "Cancel", "Close", "Reading the file", "Fetching the file",
        "This tool cannot be imported", "1 problem|\\d+ problems",
        // confirmation sheet
        ".{1,40} .{0,20}", "Review before you import", "Author", "Not given \\(unverified\\)|.{1,40} \\(unverified\\)", "Needs", "Network", "Inputs", "SHA-256", "None",
        "This tool can contact any address", "This tool can contact addresses on your network", ".{1,40}, \\w+(, optional)?", "View source", "Hide source", "\\d+ lines, \\d+ characters",
        "Import", "Replace", "Importing only reads the description\\. The script runs when you run the tool\\.",
        // replace diff
        "Replaces .{1,40} .{1,20} with .{1,20}", "The script is identical to the installed one", "Network: from .+ to .+", "Network: unchanged", "SHA-256 from [0-9a-f]{1,64} to [0-9a-f]{1,64}",
        "New inputs: .+", "Removed inputs: .+", "Changed inputs: .+", "The installed tool is broken, so its inputs cannot be compared",
        "This version reaches further than the installed one: from .+ to .+", "I allow it to contact .+",
        // saving and removing
        "The tool could not be saved", "The tool could not be removed", "(Imported|Replaced) .{1,40}", "Removed .{1,40}",
        "the device only|your network|any address",
    ).map { Regex(it) }

    private fun all(): List<String> = buildList {
        addAll(listOf(
            ImportCopy.FILE_MISSING, ImportCopy.FILE_CHANGED, ImportCopy.FILE_UNREADABLE, ImportCopy.FILE_NOT_TEXT, ImportCopy.NOT_IN_LIST, ImportCopy.LIST_UNREADABLE, ImportCopy.KEY_MISMATCH,
            ImportCopy.cannotLoad("boom"), ImportCopy.cannotLoad("x".repeat(500)), ImportCopy.SAVE_FAILED, ImportCopy.REMOVE_FAILED,
            ImportCopy.imported("Scanner", false), ImportCopy.imported("Scanner", true), ImportCopy.removed("Scanner"), ImportCopy.removed("y".repeat(100)),
        ))
        addAll(listOf(
            ImportCopy.UNEXPECTED, ImportCopy.FILE_UNOPENED, ImportCopy.TOO_LARGE, ImportCopy.EMPTY, ImportCopy.URL_BAD, ImportCopy.URL_NOT_HTTPS, ImportCopy.FETCH_REFUSED, ImportCopy.FETCH_REDIRECTS,
            ImportCopy.FETCH_STATUS, ImportCopy.FETCH_CONNECTION, ImportCopy.FETCH_TIMEOUT, ImportCopy.downgrade("2.0", "1.9"), ImportCopy.downgrade("x".repeat(50), "y".repeat(50)),
            ImportCopy.STALE, ImportCopy.WIDENING_NEEDS_YES, ImportCopy.IMPORT_ROW, ImportCopy.IMPORT_SUPPORT, ImportCopy.IMPORT_TITLE, ImportCopy.FROM_FILE, ImportCopy.FROM_URL, ImportCopy.URL_LABEL,
            ImportCopy.URL_FETCH, ImportCopy.CANCEL, ImportCopy.CLOSE, ImportCopy.WORKING_FILE, ImportCopy.WORKING_URL, ImportCopy.FAILED_TITLE, ImportCopy.errorsCount(1), ImportCopy.errorsCount(4),
            ImportCopy.reviewTitle("My tool", "1.0"), ImportCopy.REVIEW_NEW, ImportCopy.ROW_AUTHOR, ImportCopy.authorUnverified("Ann"), ImportCopy.authorUnverified(null), ImportCopy.authorUnverified(" "),
            ImportCopy.ROW_NEEDS, ImportCopy.ROW_NETWORK, ImportCopy.ROW_INPUTS, ImportCopy.ROW_SHA, ImportCopy.NO_INPUTS, ImportCopy.CALLOUT_ANY, ImportCopy.CALLOUT_LAN,
            ImportCopy.inputLine("Path", "text", true), ImportCopy.inputLine("Path", "text", false), ImportCopy.VIEW_SOURCE, ImportCopy.HIDE_SOURCE, ImportCopy.sourceSize(120, 4),
            ImportCopy.IMPORT_BUTTON, ImportCopy.REPLACE_BUTTON, ImportCopy.NOTHING_RUNS,
            ImportCopy.replaces("My tool", "1.0", "1.1"), ImportCopy.SAME_SCRIPT, ImportCopy.scopeChange("the device only", "any address"), ImportCopy.SCOPE_SAME,
            ImportCopy.shaChange("a".repeat(64), "b".repeat(64)), ImportCopy.inputsAdded(listOf("A (text)", "B (number)")), ImportCopy.inputsRemoved(listOf("A (text)")),
            ImportCopy.inputsChanged(listOf("A (text)")), ImportCopy.INPUTS_UNKNOWN, ImportCopy.widened("the device only", "any address"), ImportCopy.widenedYes("any address"),
        ))
        NetworkScope.entries.forEach { add(ImportCopy.scopeWord(it)) }
    }

    @Test fun `every string is in the table and has no em-dash or disclaimer`() {
        val bad = all().filterNot { s -> templates.any { it.matches(s) } }
        assertTrue("not in the table: $bad", bad.isEmpty())
        all().forEach { s ->
            assertFalse("em-dash in '$s'", s.contains(0x2014.toChar()))
            assertFalse("disclaimer in '$s'", s.contains("please", ignoreCase = true) || s.contains("sorry", ignoreCase = true) || s.contains("legal", ignoreCase = true))
        }
    }
}
