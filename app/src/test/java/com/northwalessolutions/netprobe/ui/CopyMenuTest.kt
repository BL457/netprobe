package com.northwalessolutions.netprobe.ui

import com.northwalessolutions.netprobe.engine.identify.DeviceType
import org.junit.Assert.*
import org.junit.Test

/** Long-press copy menus (Director ruling R4): the item list, the text each item copies and the copy templates. */
class CopyMenuTest {
    @Test fun `a device with a MAC offers IP, MAC, name and details in that order`() {
        assertEquals(listOf("Copy IP", "Copy MAC", "Copy name", "Copy details"), DeviceCopy.items("00:11:32:9a:1f:e4").map { it.label })
    }
    @Test fun `a device without a MAC hides Copy MAC`() {
        assertEquals(listOf("Copy IP", "Copy name", "Copy details"), DeviceCopy.items(null).map { it.label })
        assertEquals(listOf("Copy IP", "Copy name", "Copy details"), DeviceCopy.items("  ").map { it.label })
    }
    @Test fun `the details block lists name, IP, MAC, vendor, model, type and sorted open ports`() {
        val text = DeviceCopy.details("CHNAS", "10.0.3.59", "00:11:32:9a:1f:e4", "Synology", "DS220+", DeviceType.NAS, listOf(5000, 22, 445))
        assertEquals("Name: CHNAS\nIP: 10.0.3.59\nMAC: 00:11:32:9a:1f:e4\nVendor: Synology\nModel: DS220+\nType: NAS\nOpen ports: 22, 445, 5000", text)
    }
    @Test fun `the details block drops what is missing and names an unknown type`() {
        val text = DeviceCopy.details("10.0.3.77", "10.0.3.77", null, null, "", DeviceType.UNKNOWN, emptyList())
        assertEquals("Name: 10.0.3.77\nIP: 10.0.3.77\nType: Unknown", text)
    }
    @Test fun `each pick copies its own text`() {
        val d = { "block" }
        assertEquals("10.0.3.59", DeviceCopy.text(CopyKind.IP, "CHNAS", "10.0.3.59", "aa:bb", d))
        assertEquals("aa:bb", DeviceCopy.text(CopyKind.MAC, "CHNAS", "10.0.3.59", "aa:bb", d))
        assertEquals("CHNAS", DeviceCopy.text(CopyKind.NAME, "CHNAS", "10.0.3.59", "aa:bb", d))
        assertEquals("block", DeviceCopy.text(CopyKind.DETAILS, "CHNAS", "10.0.3.59", "aa:bb", d))
        assertEquals("10.0.3.59:5000", DeviceCopy.address("10.0.3.59", 5000))
    }
    @Test fun `copy strings are in the table and carry no em-dash`() {
        val templates = listOf("Copy (IP|MAC|name|details|address)", "Copied", "(Name|IP|MAC|Vendor|Model|Type|Open ports): .+").map { Regex(it) }
        val strings = CopyKind.entries.map { it.label } + listOf(CopyMenuCopy.COPIED, CopyMenuCopy.line(CopyMenuCopy.NAME, "x"), CopyMenuCopy.line(CopyMenuCopy.OPEN_PORTS, "22"),
            CopyMenuCopy.line(CopyMenuCopy.VENDOR, "v"), CopyMenuCopy.line(CopyMenuCopy.MODEL, "m"), CopyMenuCopy.line(CopyMenuCopy.TYPE, "t"), CopyMenuCopy.line(CopyMenuCopy.IP, "i"),
            CopyMenuCopy.line(CopyMenuCopy.MAC, "m"))
        strings.forEach { s -> assertTrue("not in the table: '$s'", templates.any { it.matches(s) }); assertFalse(s.contains(Char(0x2014))) }
    }
}
