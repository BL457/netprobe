package com.northwalessolutions.netprobe.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.discovery.MacSource
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentifier
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentity
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.ui.home.RadarCollector
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress

/** F1 (Phase 3A device QA): the radar write goes through the not-weaker guard for the same device, and always writes for a new MAC on the IP. */
@RunWith(AndroidJUnit4::class)
class RadarIdentityGuardTest {
    private lateinit var db: NetProbeDb
    private lateinit var hosts: HostRepository
    private val piMac = "e4:5f:01:e4:22:dc"
    private val piHole = DeviceIdentity(null, "Pi-hole", "Linux (Debian)", DeviceType.SERVER, 90, listOf("httpTitle: Pi-hole btast2"))
    private val ip = InetAddress.getByName("10.0.3.170") as Inet4Address
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NetProbeDb::class.java).build()
        hosts = HostRepository(db)
    }
    @After fun tearDown() { db.close() }
    private fun collector() = RadarCollector(hosts, PortRepository(db), DeviceIdentifier(emptyList()), { null }, Mutex()) { emptySet() }

    @Test fun aRadarRunNeverLowersTheSameDevicesIdentity() = runBlocking {
        val c = collector()
        c.store(1, DiscoveredHost(ip, mac = piMac, macSource = MacSource.NEIGH), 1L)
        val id = db.hosts().find(1, "10.0.3.170")!!.id
        hosts.setIdentity(id, piHole)
        c.store(1, DiscoveredHost(ip, mac = piMac, macSource = MacSource.NEIGH), 2L)   // identifies as UNKNOWN 0
        val row = hosts.byIdOnce(id)!!
        assertEquals("Pi-hole", row.identModel); assertEquals(90, row.identConfidence); assertEquals(DeviceType.SERVER, row.deviceType)
    }
    @Test fun aNewMacOnTheIpIsANewDeviceAndItsIdentityIsWritten() = runBlocking {
        val c = collector()
        c.store(1, DiscoveredHost(ip, mac = piMac, macSource = MacSource.NEIGH), 1L)
        val id = db.hosts().find(1, "10.0.3.170")!!.id
        hosts.setIdentity(id, piHole)
        c.store(1, DiscoveredHost(ip, mac = "aa:bb:cc:00:11:22", macSource = MacSource.NEIGH), 2L)
        val row = hosts.byIdOnce(id)!!
        assertEquals(null, row.identModel); assertEquals(0, row.identConfidence); assertEquals(DeviceType.UNKNOWN, row.deviceType)
    }
}
