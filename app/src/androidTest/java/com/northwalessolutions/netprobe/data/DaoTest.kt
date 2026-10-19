package com.northwalessolutions.netprobe.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.discovery.MacSource
import com.northwalessolutions.netprobe.engine.discovery.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: NetProbeDb
    @Before fun setUp() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NetProbeDb::class.java).build() }
    @After fun tearDown() { db.close() }

    @Test fun upsertKeepsAnnotationsAndRefreshesScanData() = runBlocking {
        val hosts = HostRepository(db); val ip = InetAddress.getByName("192.168.1.20") as Inet4Address
        val id = hosts.upsertDiscovered(1, DiscoveredHost(ip, mdnsName = "Desk", sources = setOf(Source.MDNS)), runId = 1)
        hosts.setStatus(id, HostStatus.INTERESTING); hosts.setNote(id, "check me"); hosts.setCustomName(id, "My PC")
        val id2 = hosts.upsertDiscovered(1, DiscoveredHost(ip, mac = "00:11:22:33:44:55", netbiosName = "DESKTOP"), runId = 2)
        assertEquals(id, id2)
        val h = hosts.host(id).first()!!
        assertEquals(HostStatus.INTERESTING, h.status); assertEquals("check me", h.note); assertEquals("My PC", h.customName)
        assertEquals("00:11:22:33:44:55", h.mac); assertEquals("Desk", h.mdnsName); assertEquals("DESKTOP", h.netbiosName); assertEquals(2L, h.lastDiscoveryRun)
    }
    @Test fun macRankDecidesAndMdnsTxtUnionsAcrossEmissions() = runBlocking {
        val hosts = HostRepository(db); val ip = InetAddress.getByName("192.168.1.30") as Inet4Address
        val id = hosts.upsertDiscovered(1, DiscoveredHost(ip, mac = "00:11:22:33:44:99", macSource = MacSource.EUI64, mdnsTxt = mapOf("_smb._tcp" to emptyMap())), runId = 1)
        hosts.upsertDiscovered(1, DiscoveredHost(ip, mac = "de:ad:be:ef:00:01", macSource = MacSource.NEIGH, mdnsTxt = mapOf("_device-info._tcp" to mapOf("model" to "Mac14,2"))), runId = 1)
        hosts.upsertDiscovered(1, DiscoveredHost(ip, mac = "00:11:22:33:44:77", macSource = MacSource.NAME), runId = 1)   // lower rank must not replace the netlink MAC
        assertEquals("de:ad:be:ef:00:01", hosts.host(id).first()!!.mac)
        assertEquals(setOf("_smb._tcp", "_device-info._tcp"), hosts.mdnsTypesFor(id))
        assertEquals("Mac14,2", hosts.toDiscovered(hosts.byIdOnce(id)!!).mdnsTxt["_device-info._tcp"]!!["model"])
    }
    @Test fun portCountsGroupByHost() = runBlocking {
        val ports = PortRepository(db)
        ports.upsertOpen(7, 22, com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo("SSH", confidence = com.northwalessolutions.netprobe.engine.serviceid.Confidence.PORT_TABLE))
        ports.upsertOpen(7, 80, com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo("HTTP", confidence = com.northwalessolutions.netprobe.engine.serviceid.Confidence.PORT_TABLE))
        assertEquals(mapOf(7L to 2), ports.counts().first())
    }
    @Test fun uniqueIndexPerNetwork() = runBlocking {
        val hosts = HostRepository(db); val ip = InetAddress.getByName("192.168.1.1") as Inet4Address
        val a = hosts.upsertDiscovered(1, DiscoveredHost(ip), 1); val b = hosts.upsertDiscovered(2, DiscoveredHost(ip), 1)
        assertNotEquals(a, b); assertEquals(1, hosts.hostsForNetwork(1).first().size)
    }
}
