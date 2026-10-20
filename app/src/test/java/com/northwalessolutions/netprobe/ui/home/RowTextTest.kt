package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Test

class RowTextTest {
    private fun row(ip: String = "10.0.3.59", name: String = "CHNAS", identity: String = "Synology DS220+", mac: String? = "00:11:32:9a:1f:e4",
                    other: String? = null, status: HostStatus = HostStatus.UNMARKED, gateway: Boolean = false, stale: Boolean = false, open: Int = 0, id: Long = 1) =
        HostRow(id, ip, name, identity, DeviceType.NAS, open, status, gateway, stale, mac, other ?: "10.0.3.0/24", other != null)

    @Test fun `ip with identity uses two spaces`() { assertEquals("10.0.3.59  Synology DS220+", RowText.dataLine(row(), null)) }
    @Test fun `unknown identity falls back to the mac`() {
        assertEquals("10.0.3.59  00:11:32:9a:1f:e4", RowText.dataLine(row(identity = "Unknown device"), null))
        assertEquals("10.0.3.59  00:11:32:9a:1f:e4", RowText.dataLine(row(identity = "Unknown device, found via mDNS"), null))
        assertEquals("10.0.3.59  00:11:32:9a:1f:e4", RowText.dataLine(row(identity = " "), null))
    }
    @Test fun `ip alone when nothing else is known`() { assertEquals("10.0.3.59", RowText.dataLine(row(identity = "Unknown device", mac = null), null)) }
    @Test fun `name equal to the ip shows identity or mac only`() {
        assertEquals("Synology DS220+", RowText.dataLine(row(name = "10.0.3.59"), null))
        assertEquals("00:11:32:9a:1f:e4", RowText.dataLine(row(name = "10.0.3.59", identity = "Unknown device"), null))
        assertEquals("10.0.3.59", RowText.dataLine(row(name = "10.0.3.59", identity = "Unknown device", mac = null), null))
    }
    @Test fun `no other subnet suffix`() { assertEquals("10.0.4.20  Philips Hue Bridge", RowText.dataLine(row(ip = "10.0.4.20", identity = "Philips Hue Bridge", other = "10.0.4.0/24"), null)) }
    @Test fun `own phone row shows the supplied model`() {
        assertEquals("10.0.3.242  Pixel 7 Pro", RowText.dataLine(row(ip = "10.0.3.242", name = "This phone", identity = "Android device"), "Pixel 7 Pro"))
    }
    @Test fun `merged row description`() {
        assertEquals("Ubiquiti gateway, gateway, 10.0.3.1, 3 open ports", RowText.description(row(ip = "10.0.3.1", name = "Ubiquiti gateway", gateway = true, open = 3), null))
        assertEquals("CHNAS, 10.0.3.59, 1 open port, marked Interesting, not seen in the last scan",
            RowText.description(row(open = 1, status = HostStatus.INTERESTING, stale = true), null))
        assertEquals("CHNAS, 10.0.3.59", RowText.description(row(), null))
    }
    @Test fun `group headers are fixed wording with no count and no network name`() {
        assertEquals("Home subnet 10.0.3.0/24", GroupHeaders.text("10.0.3.0/24", local = true))
        assertEquals("Other subnet 10.0.4.0/24", GroupHeaders.text("10.0.4.0/24", local = false))
    }
    @Test fun `groups put local first then others by network address`() {
        val rows = listOf(row(id = 1, ip = "10.0.3.1", gateway = true), row(id = 2, ip = "10.0.10.5", other = "10.0.10.0/24"),
            row(id = 3, ip = "10.0.3.9"), row(id = 4, ip = "10.0.4.20", other = "10.0.4.0/24"))
        val g = HomeGroups.of(rows)
        assertEquals(listOf("10.0.3.0/24", "10.0.4.0/24", "10.0.10.0/24"), g.map { it.subnet })
        assertEquals(listOf(true, false, false), g.map { it.local })
        assertEquals(listOf(1L, 3L), g[0].rows.map { it.id })   // input order (gateway first, then IP) is kept
    }
    @Test fun `a single group still has its header and empty groups are omitted`() {
        assertEquals(listOf("10.0.3.0/24"), HomeGroups.of(listOf(row(ip = "10.0.3.9"))).map { it.subnet })
        assertEquals(emptyList<HostGroup>(), HomeGroups.of(emptyList()))
    }
}
