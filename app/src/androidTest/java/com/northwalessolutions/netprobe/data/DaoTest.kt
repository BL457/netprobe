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
            listOf(async(Dispatchers.Default) { repeat(20) { repo.ensure(env) } }, async(Dispatchers.Default) { repeat(20) { repo.ensure(env) } }).awaitAll()
        }
        assertEquals(1, count("networks"))
    }
}
