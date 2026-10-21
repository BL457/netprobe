package com.northwalessolutions.netprobe.ui.manual

import org.junit.Assert.*
import org.junit.Test

/** 3D spec 6: the Tools root's two strings, as templates, with no em-dash. */
class ToolsCopyTest {
    private val templates = listOf("Tools", "Wordlists").map { Regex(it) }
    @Test fun `both strings are in the table`() {
        listOf(ToolsCopy.TITLE, ToolsCopy.WORDLISTS).forEach { s -> assertTrue(s, templates.any { it.matches(s) }); assertFalse(s, s.contains(0x2014.toChar())) }
        assertEquals("Tools", ToolsCopy.TITLE); assertEquals("Wordlists", ToolsCopy.WORDLISTS)
    }
}
