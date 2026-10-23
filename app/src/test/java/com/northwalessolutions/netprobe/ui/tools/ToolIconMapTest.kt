package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.tools.ToolIcon
import org.junit.Assert.*
import org.junit.Test

/** Spec section 6: the fixed set and its Material names; an unknown name falls back to the terminal icon. */
class ToolIconMapTest {
    @Test fun `every name in the set maps to the Material icon the spec names`() {
        val expected = mapOf("web" to "Language", "search" to "Search", "lock" to "Lock", "folder" to "FolderOpen", "share" to "Share", "dns" to "Dns",
            "terminal" to "Terminal", "fingerprint" to "Fingerprint", "network" to "Lan", "wifi" to "Wifi", "info" to "Info", "bolt" to "Bolt")
        assertEquals(expected.keys, ToolIcon.entries.map { it.id }.toSet())
        expected.forEach { (name, material) -> assertEquals(name, material, ToolIconMap.materialName(name)) }
        ToolIcon.entries.forEach { assertEquals(expected.getValue(it.id), ToolIconMap.materialName(it)) }
    }
    @Test fun `unknown, blank, null and differently cased names fall back to terminal`() {
        listOf("rocket", "", " ", "WEB", "Web", "folder-open", null).forEach { assertEquals("name '$it'", "Terminal", ToolIconMap.materialName(it)) }
        assertEquals(ToolIcon.TERMINAL, ToolIcon.parse("nope"))
    }
    @Test fun `each vector is the Material icon its name says`() {
        ToolIcon.entries.forEach { assertEquals(it.id, "Filled." + ToolIconMap.materialName(it), ToolIcons.vector(it).name) }
    }
}
