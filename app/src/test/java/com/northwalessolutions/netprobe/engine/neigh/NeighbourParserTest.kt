package com.northwalessolutions.netprobe.engine.neigh

import org.junit.Assert.*
import org.junit.Test

class NeighbourParserTest {
    @Test fun `parses entries and lower-cases macs`() {
        val r = NeighbourParser.parse(arrayOf("192.168.1.1|AA:BB:CC:DD:EE:FF|REACHABLE", "192.168.1.20|00:11:22:33:44:55|STALE"))
        val list = r.getOrThrow()
        assertEquals(2, list.size)
        assertEquals(NeighbourEntry("192.168.1.1", "aa:bb:cc:dd:ee:ff", "REACHABLE"), list[0])
    }
    @Test fun `drops entries without a usable mac or in FAILED or INCOMPLETE state`() {
        val list = NeighbourParser.parse(arrayOf(
            "192.168.1.2||FAILED", "192.168.1.3|00:00:00:00:00:00|REACHABLE",
            "192.168.1.4|00:11:22:33:44:55|INCOMPLETE", "192.168.1.5|00:11:22:33:44:66|DELAY")).getOrThrow()
        assertEquals(listOf("192.168.1.5"), list.map { it.ip })
    }
    @Test fun `ERR line becomes NeighbourUnavailable`() {
        val r = NeighbourParser.parse(arrayOf("ERR|13|Permission denied"))
        assertTrue(r.exceptionOrNull() is NeighbourUnavailable)
        assertEquals("errno 13: Permission denied", r.exceptionOrNull()!!.message)
    }
    @Test fun `malformed lines are skipped not thrown`() {
        val list = NeighbourParser.parse(arrayOf("garbage", "", "1.2.3.4|zz|REACHABLE", "1.2.3.5|00:11:22:33:44:55|REACHABLE")).getOrThrow()
        assertEquals(1, list.size)
    }
    @Test fun `proc net arp lines parse and incomplete entries are dropped`() {
        val text = "IP address       HW type     Flags       HW address            Mask     Device\n" +
            "192.168.1.1      0x1         0x2         AA:BB:CC:DD:EE:FF     *        wlan0\n" +
            "192.168.1.7      0x1         0x0         00:00:00:00:00:00     *        wlan0\n" +
            "192.168.1.8      0x1         0x2         00:11:22:33:44:66     *        wlan0\n" +
            "garbage line\n"
        val list = NeighbourParser.parseProcArp(text)
        assertEquals(listOf("192.168.1.1", "192.168.1.8"), list.map { it.ip })
        assertEquals("aa:bb:cc:dd:ee:ff", list[0].mac)
        assertTrue(NeighbourParser.parseProcArp("").isEmpty())
    }
}
