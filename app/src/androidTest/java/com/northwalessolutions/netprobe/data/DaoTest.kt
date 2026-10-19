package com.northwalessolutions.netprobe.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.discovery.MacSource
import com.northwalessolutions.netprobe.engine.discovery.Source
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentity
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: NetProbeDb
    @Before fun setUp() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NetProbeDb::class.java).build() }
    @After fun tearDown() { db.close() }

    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private fun hostEntity(networkId: Long, ip: String) = HostEntity(
        networkId = networkId, ip = ip, mac = null, reverseDnsName = null, mdnsName = null, netbiosName = null, upnpName = null, upnpManufacturer = null, upnpModel = null, vendor = null,
        deviceType = DeviceType.UNKNOWN, customName = null, identVendor = null, identModel = null, identOs = null, identConfidence = 0, identReasons = emptyList(),
        snmpSysDescr = null, httpTitle = null, mdnsTxtJson = null, status = HostStatus.UNMARKED, note = "", isGateway = false, firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = 0)
    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test fun upsertKeepsAnnotationsAndRefreshesScanData() = runBlocking {
        val hosts = HostRepository(db); val ip = ip("192.168.1.20")
        val id = hosts.upsertDiscovered(1, DiscoveredHost(ip, mdnsName = "Desk", sources = setOf(Source.MDNS)), runId = 1)
        hosts.setStatus(id, HostStatus.INTERESTING); hosts.setNote(id, "check me"); hosts.setCustomName(id, "My PC")
        val id2 = hosts.upsertDiscovered(1, DiscoveredHost(ip, mac = "00:11:22:33:44:55", netbiosName = "DESKTOP"), runId = 2)
        assertEquals(id, id2)
        hosts.setIdentity(id, DeviceIdentity("Dell", "XPS", "Windows", DeviceType.UNKNOWN, 80, listOf("reason")))
        val h = hosts.host(id).first()!!
        assertEquals(HostStatus.INTERESTING, h.status); assertEquals("check me", h.note); assertEquals("My PC", h.customName)
        assertEquals("00:11:22:33:44:55", h.mac); assertEquals("Desk", h.mdnsName); assertEquals("DESKTOP", h.netbiosName); assertEquals(2L, h.lastDiscoveryRun)
        assertEquals("Dell", h.identVendor); assertEquals(listOf("reason"), h.identReasons); assertEquals(80, h.identConfidence)
        // A re-emission with a null mdnsName must leave the stored name intact.
        hosts.upsertDiscovered(1, DiscoveredHost(ip, mdnsName = null), runId = 3)
        assertEquals("Desk", hosts.host(id).first()!!.mdnsName)
    }
    @Test fun macRankDecidesAndMdnsTxtUnionsAcrossEmissions() = runBlocking {
        val hosts = HostRepository(db); val ip = ip("192.168.1.30")
        val id = hosts.upsertDiscovered(1, DiscoveredHost(ip, mac = "00:11:22:33:44:99", macSource = MacSource.EUI64, mdnsTxt = mapOf("_smb._tcp" to emptyMap())), runId = 1)
        hosts.upsertDiscovered(1, DiscoveredHost(ip, mac = "de:ad:be:ef:00:01", macSource = MacSource.NEIGH, mdnsTxt = mapOf("_device-info._tcp" to mapOf("model" to "Mac14,2"))), runId = 1)
        hosts.upsertDiscovered(1, DiscoveredHost(ip, mac = "00:11:22:33:44:77", macSource = MacSource.NAME), runId = 1)   // lower rank must not replace the netlink MAC
        assertEquals("de:ad:be:ef:00:01", hosts.host(id).first()!!.mac)
        assertEquals(setOf("_smb._tcp", "_device-info._tcp"), hosts.mdnsTypesFor(id))
        val d = hosts.toDiscovered(hosts.byIdOnce(id)!!)
        assertEquals("Mac14,2", d.mdnsTxt["_device-info._tcp"]!!["model"])
        assertEquals(MacSource.NEIGH, d.macSource)
    }
    @Test fun portCountsGroupByHost() = runBlocking {
        val ports = PortRepository(db)
        ports.upsertOpen(7, 22, ServiceInfo("SSH", confidence = Confidence.PORT_TABLE))
        ports.upsertOpen(7, 80, ServiceInfo("HTTP", confidence = Confidence.PORT_TABLE))
        assertEquals(mapOf(7L to 2), ports.counts().first())
    }
    @Test fun uniqueIndicesRejectDuplicates() = runBlocking {
        db.hosts().insert(hostEntity(1, "192.168.1.1"))
        assertThrows(SQLiteConstraintException::class.java) { runBlocking { db.hosts().insert(hostEntity(1, "192.168.1.1")) } }
        db.hosts().insert(hostEntity(2, "192.168.1.1"))   // same IP in a different network is allowed
        assertEquals(1, db.hosts().forNetwork(2).first().size)

        fun port() = PortEntity(hostId = 5, port = 22, serviceName = "SSH", product = null, version = null, detail = null, confidence = Confidence.PORT_TABLE, lastSeen = 0)
        db.ports().insert(port())
        assertThrows(SQLiteConstraintException::class.java) { runBlocking { db.ports().insert(port()) } }

        fun net() = NetworkEntity(signature = "sig", displayName = "n", gatewayIp = null, prefixLength = 24, ssid = null, lastSeen = 0)
        db.networks().insert(net())
        assertThrows(SQLiteConstraintException::class.java) { runBlocking { db.networks().insert(net()) } }
        Unit
    }
    @Test fun annotationsSurviveConcurrentUpserts() = runBlocking {
        val hosts = HostRepository(db); val ip = ip("192.168.1.40")
        val id = hosts.upsertDiscovered(1, DiscoveredHost(ip), runId = 0)
        val steps = 150
        val written = ConcurrentLinkedQueue<String>()   // every note, recorded just before it is written
        var lastSeen = 0; var samples = 0
        coroutineScope {
            val writer = launch(Dispatchers.Default) {
                for (i in 1..steps) {
                    written.add("n$i"); hosts.setNote(id, "n$i")
                    hosts.setStatus(id, if (i % 2 == 0) HostStatus.IGNORED else HostStatus.INTERESTING)
                    hosts.setCustomName(id, "c$i")
                }
            }
            // The scanner overlaps every writer step; a stale whole-row write would re-apply an older note.
            launch(Dispatchers.Default) {
                var run = 1L
                while (writer.isActive) {
                    hosts.upsertDiscovered(1, DiscoveredHost(ip, mdnsName = "m$run"), runId = run++)
                    val note = hosts.byIdOnce(id)!!.note
                    if (note.isNotEmpty()) {
                        assertTrue("note $note was never written", note in written)
                        val n = note.removePrefix("n").toInt()
                        assertTrue("stale note $note re-applied after n$lastSeen", n >= lastSeen)
                        lastSeen = n; samples++
                    }
                }
            }
        }
        val h = hosts.host(id).first()!!
        assertTrue("scanner never overlapped the writer", samples > 0)
        assertEquals("n$steps", h.note); assertEquals(HostStatus.IGNORED, h.status); assertEquals("c$steps", h.customName)
    }
    @Test fun ensureIsRaceSafe() = runBlocking {
        val repo = NetworkRepository(db)
        val env = NetworkEnv(ip("192.168.7.5"), 24, ip("192.168.7.1"), emptyList(), "Home", null, Transport.WIFI)
        coroutineScope {
            listOf(async(Dispatchers.Default) { repeat(20) { repo.ensure(env, GW_MAC) } }, async(Dispatchers.Default) { repeat(20) { repo.ensure(env, GW_MAC) } }).awaitAll()
        }
        assertEquals(1, count("networks"))
    }
    @Test fun locationGrantKeepsTheSameNetwork() = runBlocking {
        val repo = NetworkRepository(db); val hosts = HostRepository(db)
        val bare = NetworkEnv(ip("192.168.8.5"), 24, ip("192.168.8.1"), emptyList(), null, null, Transport.WIFI)
        val named = bare.copy(ssid = "Home", bssid = "aa:bb:cc:dd:ee:ff")
        val before = repo.ensure(bare, GW_MAC)
        val hid = hosts.upsertDiscovered(before.id, DiscoveredHost(ip("192.168.8.20")), 1L)
        db.hosts().setNote(hid, "keep me")
        val after = repo.ensure(named, GW_MAC)
        assertEquals(before.id, after.id); assertEquals("Home", after.ssid); assertEquals(1, count("networks"))
        assertEquals("keep me", hosts.byIdOnce(hid)!!.note)
        // Roaming to another access point (new BSSID) and turning Location off again keep the same row.
        assertEquals(after.id, repo.ensure(named.copy(bssid = "aa:bb:cc:dd:ee:01"), GW_MAC).id)
        assertEquals(after.id, repo.ensure(bare, GW_MAC).id)
        assertEquals(1, count("networks"))
    }
    @Test fun ensureAdoptsARowKeyedByTheOldRule() = runBlocking {
        val repo = NetworkRepository(db); val hosts = HostRepository(db)
        // A row written by the BSSID-based signature: its hash matches nothing the new rule produces.
        val oldId = db.networks().insert(NetworkEntity(signature = "old-bssid-signature", displayName = "Home", gatewayIp = "10.0.3.1", prefixLength = 24, ssid = "Home", lastSeen = 0))
        val hid = hosts.upsertDiscovered(oldId, DiscoveredHost(ip("10.0.3.50")), 1L)
        hosts.setStatus(hid, HostStatus.INTERESTING); hosts.setNote(hid, "QA note NAS")
        val env = NetworkEnv(ip("10.0.3.242"), 24, ip("10.0.3.1"), emptyList(), null, null, Transport.WIFI)
        val n = repo.ensure(env, GW_MAC)
        assertEquals(oldId, n.id); assertEquals(NetworkSignature.of(env, GW_MAC), n.signature); assertEquals("Home", n.ssid); assertEquals("Home", n.displayName)
        assertEquals(NetworkSignature.of(env, GW_MAC), db.networks().bySignature(NetworkSignature.of(env, GW_MAC))!!.signature)
        val h = hosts.byIdOnce(hid)!!
        assertEquals(HostStatus.INTERESTING, h.status); assertEquals("QA note NAS", h.note); assertEquals(1, count("networks"))
        // An unreadable MAC later still lands on the same row.
        assertEquals(oldId, repo.ensure(env, null).id); assertEquals(oldId, repo.ensure(env, GW_MAC).id); assertEquals(1, count("networks"))
    }
    @Test fun anotherSiteWithTheSameAddressingIsNotMerged() = runBlocking {
        val repo = NetworkRepository(db); val hosts = HostRepository(db)
        val env = NetworkEnv(ip("192.168.1.5"), 24, ip("192.168.1.1"), emptyList(), null, null, Transport.WIFI)
        val siteA = repo.ensure(env, GW_MAC)
        hosts.upsertDiscovered(siteA.id, DiscoveredHost(ip("192.168.1.1"), mac = GW_MAC, macSource = MacSource.NEIGH, isGateway = true), 1L)
        val siteB = repo.ensure(env, "00:11:22:33:44:55")
        assertNotEquals(siteA.id, siteB.id); assertEquals(2, count("networks"))
        assertEquals(siteA.id, repo.ensure(env, GW_MAC).id)
    }
    @Test fun servicesForReturnsStoredPorts() = runBlocking {
        val ports = PortRepository(db)
        ports.upsertOpen(9, 22, ServiceInfo("SSH", "OpenSSH", "9.6p1", "Ubuntu-3ubuntu13", Confidence.CONFIRMED))
        ports.upsertOpen(9, 445, ServiceInfo("SMB", confidence = Confidence.PORT_TABLE))
        ports.upsertOpen(10, 80, ServiceInfo("HTTP", confidence = Confidence.PORT_TABLE))
        val s = ports.servicesFor(9)
        assertEquals(setOf(22, 445), s.keys)
        assertEquals(ServiceInfo("SSH", "OpenSSH", "9.6p1", "Ubuntu-3ubuntu13", Confidence.CONFIRMED), s[22])
        assertEquals(emptyMap<Int, ServiceInfo>(), ports.servicesFor(11))
    }

    private companion object { const val GW_MAC = "e0:63:da:26:f1:37" }
}
