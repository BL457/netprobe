package com.northwalessolutions.netprobe.ui.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every CatalogCopy string against its template, with no em-dash, no disclaimer and no unbounded echo of catalog text. */
class CatalogCopyTest {
    private val templates = listOf(
        ".{1,40}: .+", ".{1,40} is listed twice, the second line was ignored", "The catalog lists more than 200 tools, the rest were ignored",
        "The catalog line is missing a field or has the wrong kind of value", "The id is not valid", "The file name is not the id plus [.]js",
        "The name is missing or longer than 40 characters", "The version is not valid", "The SHA-256 is not 64 hex digits", "The network is not target, lan or any",
        "The description is longer than 200 characters", "Only a tool with the network set to lan can declare broadcast",
    ).map { Regex(it) }

    private fun all(): List<String> = listOf(
        CatalogCopy.indexLineBad("my-tool", CatalogCopy.INDEX_ID), CatalogCopy.indexLineBad("x".repeat(300), CatalogCopy.INDEX_FIELDS), CatalogCopy.indexDuplicate("my-tool"),
        CatalogCopy.indexDuplicate("y".repeat(300)), CatalogCopy.INDEX_TOO_LONG, CatalogCopy.INDEX_FIELDS, CatalogCopy.INDEX_ID, CatalogCopy.INDEX_FILE, CatalogCopy.INDEX_NAME,
        CatalogCopy.INDEX_VERSION, CatalogCopy.INDEX_SHA, CatalogCopy.INDEX_NETWORK, CatalogCopy.INDEX_DESCRIPTION, CatalogCopy.INDEX_BROADCAST,
    )

    @Test fun `every string is in the table and has no em-dash or disclaimer`() {
        val bad = all().filterNot { s -> templates.any { it.matches(s) } }
        assertTrue("not in the table: $bad", bad.isEmpty())
        all().forEach { s ->
            assertFalse("em-dash in '$s'", s.contains(0x2014.toChar()))
            assertFalse("disclaimer in '$s'", s.contains("please", ignoreCase = true) || s.contains("sorry", ignoreCase = true) || s.contains("legal", ignoreCase = true))
        }
    }
}
