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
