package com.northwalessolutions.netprobe.data

import org.junit.Assert.*
import org.junit.Test

class IdentityWriteTest {
    @Test fun `identity guard replaces on equal or higher confidence only`() {   // spec 9.1 test 21, Review Focus 3
        assertFalse(IdentityWrite.replaces(storedConfidence = 90, newConfidence = 70))
        assertTrue(IdentityWrite.replaces(storedConfidence = 90, newConfidence = 90))
        assertTrue(IdentityWrite.replaces(storedConfidence = 70, newConfidence = 90))
    }
    @Test fun `software column round trips names in order without blanks or repeats`() {
        assertEquals("Home Assistant\nPlex Media Server", SoftwareColumn.encode(listOf("Home Assistant", " Plex Media Server ", "", "Home Assistant")))
        assertEquals(listOf("Home Assistant", "Plex Media Server"), SoftwareColumn.decode("Home Assistant\nPlex Media Server"))
        assertNull(SoftwareColumn.encode(emptyList())); assertEquals(emptyList<String>(), SoftwareColumn.decode(null))
    }
    @Test fun `empty edits clear every override`() {
        assertEquals(DeviceEdits(null, null, null, null), DeviceEdits())
    }
}
