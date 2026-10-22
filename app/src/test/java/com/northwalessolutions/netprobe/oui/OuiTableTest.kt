package com.northwalessolutions.netprobe.oui

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class OuiTableTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sample = "Registry,Assignment,Organization Name,Organization Address\n" +
        "MA-L,001122,\"Acme, Inc.\",\"1 Road, Town\"\n" +
        "MA-L,3C5AB4,Google LLC,Mountain View\n" +
        "MA-M,70B3D5A,\"Quoted \"\"Name\"\" Ltd\",Somewhere\n" +
        "MA-S,70B3D5A1B,Tiny Devices GmbH,Berlin\n" +
        "MA-L,ZZZZZZ,Bad Assignment,Nowhere\n" +
        "MA-L,AABBCC,,No name\n"

    private fun built(): OuiTable { val b = OuiTable.Builder(); OuiCsv.parseInto(sample, b); return b.build() }
    private fun mac(s: String) = OuiStore.parse(s)!!

    @Test fun `the csv parser reads quoted fields with commas and skips the header and bad rows`() {
        val b = OuiTable.Builder()
        assertEquals(4, OuiCsv.parseInto(sample, b))
        val t = b.build()
        assertEquals(4, t.size)
        assertEquals("Acme, Inc.", t.lookup(mac("00:11:22:33:44:55")))
        assertEquals("Google LLC", t.lookup(mac("3c:5a:b4:00:00:01")))
        assertNull("the header line is not an entry", t.lookup(mac("00:00:00:00:00:00")))
    }
    @Test fun `crlf line endings and a missing final newline parse the same`() {
        val b = OuiTable.Builder()
        assertEquals(2, OuiCsv.parseInto("Registry,Assignment,Organization Name,Organization Address\r\nMA-L,001122,A,x\r\nMA-L,001123,B,y", b))
    }
    @Test fun `the longest prefix wins`() {
        val t = built()
        assertEquals("Tiny Devices GmbH", t.lookup(mac("70:b3:d5:a1:b2:c3")))          // 36 bits
        assertEquals("Quoted \"Name\" Ltd", t.lookup(mac("70:b3:d5:a9:00:00")))        // 28 bits (A, not A1)
        assertNull("the MA-L block of that range is not in the sample", t.lookup(mac("70:b3:d5:00:00:00")))
        assertEquals("Acme, Inc.", t.lookup(mac("00:11:22:ff:ff:ff")))                // 24 bits
    }
    @Test fun `the table round trips through its binary form`() {
        val out = ByteArrayOutputStream(); built().write(out)
        val back = OuiTable.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(4, back.size)
        assertEquals("Tiny Devices GmbH", back.lookup(mac("70:b3:d5:a1:b2:c3")))
        assertEquals("Acme, Inc.", back.lookup(mac("00:11:22:00:00:01")))
    }
    @Test fun `a file that is not a table is refused`() {
        assertThrows(IOException::class.java) { OuiTable.read(ByteArrayInputStream(ByteArray(40) { 7 })) }
        val out = ByteArrayOutputStream(); built().write(out)
        assertThrows(IOException::class.java) { OuiTable.read(ByteArrayInputStream(out.toByteArray().copyOf(out.size() - 3))) }
    }

    private class Meta : OuiMeta { var info: OuiInfo? = null; override fun read() = info; override fun write(info: OuiInfo) { this.info = info } }

    @Test fun `locally administered addresses are private and never looked up, multicast gives null`() {
        val store = OuiStore(File(tmp.newFolder("a"), "oui.bin"), Meta())
        store.replace(OuiTable.Builder().also { it.add(24, 0x021122, "Would match if looked up") }.build(), 1L)
        assertEquals("Private address", store.lookup("02:11:22:33:44:55"))
        assertEquals("Private address", store.lookup("da-a1-19-00-00-01"))
        assertNull(store.lookup("01:00:5e:00:00:01"))
        assertNull(store.lookup("not a mac")); assertNull(store.lookup("00:11:22:33:44"))
    }
    @Test fun `with no table downloaded a global address names no vendor`() {
        val store = OuiStore(File(tmp.newFolder("b"), "oui.bin"), Meta())
        assertNull(store.lookup("00:11:22:33:44:55")); assertNull(store.info.value)
    }
    @Test fun `replace writes the file, records the info and a new store loads it lazily`() {
        val file = File(tmp.newFolder("c"), "oui/oui.bin"); val meta = Meta()
        OuiStore(file, meta).replace(built(), 99L)
        assertEquals(OuiInfo(99L, 4), meta.info)
        assertFalse("no temp file is left", File(file.parentFile, "oui.bin.tmp").exists())
        assertEquals("Google LLC", OuiStore(file, meta).lookup("3C:5A:B4:11:22:33"))
        assertEquals(OuiInfo(99L, 4), OuiStore(file, meta).info.value)
    }
    @Test fun `a corrupt table file reads as no table`() {
        val file = File(tmp.newFolder("d"), "oui.bin"); file.writeBytes(ByteArray(10) { 1 })
        val logged = mutableListOf<Throwable>()
        assertNull(OuiStore(file, Meta(), log = { logged += it }).lookup("00:11:22:33:44:55")); assertEquals(1, logged.size)
    }
}
