package com.northwalessolutions.netprobe.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.AppScopes
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentifier
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.ui.home.QuickScanAllLoop
import com.northwalessolutions.netprobe.ui.home.RadarCollector
import com.northwalessolutions.netprobe.ui.home.RemovedHosts
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class RemoveDeviceTest {
    private lateinit var db: NetProbeDb
    private lateinit var hosts: HostRepository
    private lateinit var ports: PortRepository
    private val lock = Mutex()
    private var removed: Set<Pair<Long, String>> = emptySet()
    private fun collector() = RadarCollector(hosts, ports, DeviceIdentifier(emptyList()), { null }, lock) { removed }
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NetProbeDb::class.java).build()
        hosts = HostRepository(db); ports = PortRepository(db)
    }
    @After fun tearDown() { db.close() }
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    /** Remove device's data steps, as DetailViewModel.removeDevice runs them under the shared lock. */
    private suspend fun remove(id: Long): HostSnapshot = lock.withLock {
        val h = hosts.byIdOnce(id)!!
        removed = RemovedHosts.added(removed, h.networkId, h.ip)
        hosts.snapshot(id)!!.also { hosts.delete(it) }
    }

    @Test fun theRadarCollectorNeverReinsertsARemovedIp() = runBlocking {
        val c = collector()
        assertTrue(c.store(1, DiscoveredHost(ip("10.0.3.59"), mdnsName = "CHNAS"), 1L))
        val id = db.hosts().find(1, "10.0.3.59")!!.id
        hosts.setNote(id, "QA note NAS")
        val snap = remove(id)
        assertFalse(c.store(1, DiscoveredHost(ip("10.0.3.59")), 1L))           // a later emission of the same run
        assertTrue(c.store(1, DiscoveredHost(ip("10.0.3.60")), 1L))
        assertNull(db.hosts().find(1, "10.0.3.59")); assertNotNull(db.hosts().find(1, "10.0.3.60"))
        hosts.restore(snap)
        assertEquals(snap, hosts.snapshot(id)); assertEquals("QA note NAS", hosts.byIdOnce(id)!!.note)
    }
    @Test fun anEmissionWaitingOnTheLockSeesTheRemove() = runBlocking {   // G2 path (b)
        val c = collector()
        c.store(1, DiscoveredHost(ip("10.0.3.61")), 1L); val id = db.hosts().find(1, "10.0.3.61")!!.id
        lock.lock()                                                            // Remove has the lock
        val pending = async(Dispatchers.Default) { c.store(1, DiscoveredHost(ip("10.0.3.61")), 1L) }
        delay(200)                                                             // the emission is now waiting on the lock
        removed = RemovedHosts.added(removed, 1, "10.0.3.61"); hosts.snapshot(id)!!.also { hosts.delete(it) }
        lock.unlock()
        assertFalse(pending.await()); assertNull(db.hosts().find(1, "10.0.3.61"))
    }
    @Test fun quickScanAllSkipsAHostRemovedBeforeItIsReached() = runBlocking {   // G2 path (a), production loop and write
        val a = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.1")), 1L)
        val b = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.2")), 1L)
        val removedIds = mutableSetOf<Long>(); val scanned = mutableListOf<Long>()
        QuickScanAllLoop.run(listOf(a, b), { it }, { RemovedHosts.skipQuickScan(removedIds, emptySet(), 1, it, "") }, ConcurrentHashMap()) { id ->
            if (id == a) { removedIds += b; remove(b) }                       // the Director removes b while a is scanned
            if (ports.upsertOpenIfHostExists(id, 80, ServiceInfo("HTTP", confidence = Confidence.PORT_TABLE))) scanned += id
        }
        assertEquals(listOf(a), scanned); assertEquals(emptyMap<Int, ServiceInfo>(), ports.servicesFor(b))
    }
    @Test fun aLateWriteForADeletedHostIsDropped() = runBlocking {
        val id = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.62")), 1L)
        remove(id)
        assertFalse(ports.upsertOpenIfHostExists(id, 22, ServiceInfo("SSH", confidence = Confidence.CONFIRMED)))
        assertEquals(emptyMap<Int, ServiceInfo>(), ports.servicesFor(id))
    }
    @Test fun removeDuringQuickScanAllLeavesNoOrphansAndTheLoopCarriesOn() = runBlocking {
        val a = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.11")), 1L)
        val b = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.12")), 1L)
        val c = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.13")), 1L)
        val jobs = ConcurrentHashMap<Long, Job>(); val scanned = mutableListOf<Long>(); val inB = CompletableDeferred<Unit>()
        val loop = launch(Dispatchers.Default) {
            QuickScanAllLoop.run(listOf(a, b, c), { it }, { false }, jobs) { id ->
                if (id == b) { ports.upsertOpenIfHostExists(b, 22, ServiceInfo("SSH", confidence = Confidence.PORT_TABLE)); inB.complete(Unit); awaitCancellation() }
                ports.upsertOpenIfHostExists(id, 80, ServiceInfo("HTTP", confidence = Confidence.PORT_TABLE)); synchronized(scanned) { scanned += id }
            }
        }
        inB.await()
        jobs.getValue(b).cancelAndJoin()                                      // Remove step 1: that host's job, and wait for it
        remove(b)                                                             // Remove step 3
        loop.join()
        assertEquals(listOf(a, c), scanned)
        assertNull(hosts.byIdOnce(b)); assertEquals(emptyMap<Int, ServiceInfo>(), ports.servicesFor(b))   // no orphan port rows
    }
    @Test fun anUnexpectedFailureIsCaughtByTheAppScopeHandler() = runBlocking {   // spec 9.3 test 6
        val id = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.80")), 1L)
        val row = hosts.byIdOnce(id)!!
        val seen = CompletableDeferred<Throwable>()
        val scope = AppScopes.create(AppScopes.handler { seen.complete(it) })
        scope.launch { db.hosts().insertAll(listOf(row)) }                    // a raw clash: restore itself can no longer clash (Task 2)
        assertTrue(withTimeout(5_000) { seen.await() } is SQLiteConstraintException)
        assertEquals(42, scope.async { 42 }.await()); scope.cancel()
    }
}
