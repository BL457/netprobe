package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import org.junit.Assert.*
import org.junit.Test

class HomeFilterTest {
    private fun row(ip: String, name: String = ip, status: HostStatus = HostStatus.UNMARKED, gw: Boolean = false, identity: String = "Unknown device") =
        HostRow(ip.hashCode().toLong(), ip, name, identity, DeviceType.UNKNOWN, 0, status, gw, false, null)
    private val rows = listOf(row("192.168.1.20", "pc", HostStatus.INTERESTING), row("192.168.1.3", "tv", identity = "Samsung QE55"), row("192.168.1.1", "router", gw = true), row("192.168.1.100", "cam", HostStatus.IGNORED))

    @Test fun `gateway first then numeric ip order`() {
        assertEquals(listOf("192.168.1.1", "192.168.1.3", "192.168.1.20", "192.168.1.100"), HomeFilter.apply(rows, StatusFilter.ALL, "").map { it.ip })
    }
    @Test fun `status filters`() {
        assertEquals(listOf("192.168.1.20"), HomeFilter.apply(rows, StatusFilter.INTERESTING, "").map { it.ip })
        assertEquals(listOf("192.168.1.1", "192.168.1.3"), HomeFilter.apply(rows, StatusFilter.UNMARKED, "").map { it.ip })
        assertTrue(HomeFilter.apply(rows, StatusFilter.INVESTIGATED, "").isEmpty())
    }
    @Test fun `query matches name ip and identity case-insensitively`() {
        assertEquals(listOf("192.168.1.3"), HomeFilter.apply(rows, StatusFilter.ALL, "samsung").map { it.ip })
        assertEquals(listOf("192.168.1.20"), HomeFilter.apply(rows, StatusFilter.ALL, "PC").map { it.ip })
        assertEquals(listOf("192.168.1.1", "192.168.1.100"), HomeFilter.apply(rows, StatusFilter.ALL, ".1").map { it.ip }.filter { it.endsWith(".1") || it.endsWith(".100") })
    }
    @Test fun `identity line rules`() {
        assertEquals("Apple MacBookPro18,3", IdentityLine.of("Apple", "MacBookPro18,3", null, DeviceType.COMPUTER, onlyMdns = false))
        assertEquals("Android Phone", IdentityLine.of(null, null, "Android", DeviceType.PHONE, onlyMdns = false))
        assertEquals("Printer", IdentityLine.of(null, null, null, DeviceType.PRINTER, onlyMdns = false))
        assertEquals("Unknown device, found via mDNS", IdentityLine.of(null, null, null, DeviceType.UNKNOWN, onlyMdns = true))
        assertEquals("Unknown device", IdentityLine.of(null, null, null, DeviceType.UNKNOWN, onlyMdns = false))
    }
    // Fix round 1: the googlecast md= model already carries the vendor ("Google Nest Hub"); do not show "Google Google Nest Hub".
    @Test fun `vendor is not repeated when the model starts with it`() {
        assertEquals("Google Nest Hub", IdentityLine.of("Google", "Google Nest Hub", null, DeviceType.MEDIA, onlyMdns = false))
        assertEquals("Google Chromecast", IdentityLine.of("Google", "Chromecast", null, DeviceType.MEDIA, onlyMdns = false))
    }
}
