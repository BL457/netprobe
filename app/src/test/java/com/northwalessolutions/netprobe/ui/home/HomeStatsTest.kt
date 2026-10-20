package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import org.junit.Assert.*
import org.junit.Test

class HomeStatsTest {
    private fun host(ip: String, run: Long) = HostEntity(networkId = 1, ip = ip, mac = null, reverseDnsName = null, mdnsName = null, netbiosName = null,
        upnpName = null, upnpManufacturer = null, upnpModel = null, vendor = null, deviceType = DeviceType.UNKNOWN, customName = null, identVendor = null,
        identModel = null, identOs = null, identConfidence = 0, identReasons = emptyList(), snmpSysDescr = null, httpTitle = null, mdnsTxtJson = null,
        status = HostStatus.UNMARKED, note = "", isGateway = false, firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = run)
    private fun row(id: Long, other: String?, stale: Boolean) = HostRow(id, "10.0.4.$id", "h$id", "x", DeviceType.UNKNOWN, 0, HostStatus.UNMARKED, false, stale, null, other ?: "10.0.3.0/24", other != null)

    @Test fun `counts are unfiltered and other subnet excludes stale hosts`() {
        val hosts = listOf(host("10.0.3.1", 100), host("10.0.4.2", 250), host("10.0.4.3", 90))
        val rows = listOf(row(1, null, false), row(2, "10.0.4.0/24", false), row(3, "10.0.4.0/24", true))
        assertEquals(HomeStats(lastScanAt = 250, totalHosts = 3, otherSubnetHosts = 1), HomeStats.of(hosts, rows))
    }
    @Test fun `no hosts means no last scan`() {
        assertEquals(HomeStats(null, 0, 0), HomeStats.of(emptyList(), emptyList()))
        assertNull(HomeStats.of(listOf(host("10.0.3.1", 0)), emptyList()).lastScanAt)
    }
    @Test fun `a scan finished this session counts as a last scan even with zero stored hosts`() {
        assertEquals(HomeStats(lastScanAt = 99, totalHosts = 0, otherSubnetHosts = 0), HomeStats.of(emptyList(), emptyList(), finishedAt = 99L))
        assertEquals(250L, HomeStats.of(listOf(host("10.0.3.1", 250)), emptyList(), finishedAt = 99L).lastScanAt)
    }
    @Test fun `finish time is recorded when scanning goes false and cleared when a scan starts`() {
        assertNull(ScanFinish.next(wasScanning = false, scanning = true, previous = 5L, now = 10L))
        assertEquals(10L, ScanFinish.next(wasScanning = true, scanning = false, previous = null, now = 10L))
        assertEquals(5L, ScanFinish.next(wasScanning = false, scanning = false, previous = 5L, now = 10L))
    }
    @Test fun `a network change drops the finish time so the old duration never sits against the new network's count`() {
        assertEquals(5L, ScanFinish.afterNetworkChange(previousNetworkId = 1L, networkId = 1L, finishedAt = 5L))
        assertNull(ScanFinish.afterNetworkChange(previousNetworkId = 1L, networkId = 2L, finishedAt = 5L))
        assertNull(ScanFinish.afterNetworkChange(previousNetworkId = 1L, networkId = null, finishedAt = 5L))
    }
}
