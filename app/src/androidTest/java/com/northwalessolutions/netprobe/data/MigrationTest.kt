package com.northwalessolutions.netprobe.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Schema 1 to 2 on a throwaway file. Never opens, creates or deletes DB_NAME, and proves the app's file is untouched (Gruff M8). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    @get:Rule val helper = MigrationTestHelper(instrumentation, NetProbeDb::class.java)

    private data class Stamp(val exists: Boolean, val length: Long, val modified: Long)
    private fun stamp(f: File) = if (f.exists()) Stamp(true, f.length(), f.lastModified()) else Stamp(false, -1, -1)
    private fun deleteTestDb() { listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(MIGRATION_TEST_DB + it).delete() } }

    @Before fun clean() { assertNotEquals(DB_NAME, MIGRATION_TEST_DB); deleteTestDb() }
    @After fun tidy() { deleteTestDb() }

    @Test fun marksNotesAndPortsSurviveTheMigration() {
        val appDb = context.getDatabasePath(DB_NAME)
        val before = stamp(appDb)   // recorded before anything else runs
        helper.createDatabase(MIGRATION_TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO networks (id, signature, displayName, gatewayIp, prefixLength, ssid, lastSeen) VALUES (1, 'sig', 'Home', '10.0.3.1', 24, 'Home', 5)")
            db.execSQL("INSERT INTO hosts (id, networkId, ip, mac, reverseDnsName, mdnsName, netbiosName, upnpName, upnpManufacturer, upnpModel, vendor, " +
                "deviceType, customName, identVendor, identModel, identOs, identConfidence, identReasons, snmpSysDescr, httpTitle, mdnsTxtJson, status, note, " +
                "isGateway, firstSeen, lastSeen, lastPortScan, lastDiscoveryRun, macSource, upnpDeviceType, upnpServer, httpServer, httpAuthRealm) VALUES " +
                "(7, 1, '10.0.3.59', '00:11:32:9a:1f:e4', 'chnas.lan', 'CHNAS', NULL, NULL, NULL, NULL, NULL, 'NAS', 'DiskStation', 'Synology', 'DS220+', NULL, " +
                "80, '[\"mDNS model DS220+\"]', NULL, NULL, NULL, 'INTERESTING', 'QA note NAS', 0, 1, 2, 3, 4, 4, NULL, NULL, NULL, NULL)")
            db.execSQL("INSERT INTO ports (id, hostId, port, serviceName, product, version, detail, confidence, lastSeen) VALUES (11, 7, 5000, 'HTTP', 'nginx', NULL, '200 OK', 'CONFIRMED', 9)")
        }
        val v2 = helper.runMigrationsAndValidate(MIGRATION_TEST_DB, 2, true, MIGRATION_1_2)
        v2.query("SELECT customName, status, note, identVendor, userType, userVendor, userModel, identSoftware FROM hosts WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("DiskStation", c.getString(0)); assertEquals("INTERESTING", c.getString(1)); assertEquals("QA note NAS", c.getString(2))
            assertEquals("Synology", c.getString(3)); for (i in 4..7) assertTrue("host column $i must be null", c.isNull(i))
        }
        v2.query("SELECT lastRunAt, forgotten, displayName FROM networks WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)); assertTrue(c.isNull(1)); assertEquals("Home", c.getString(2))
        }
        v2.query("SELECT serviceName, product, title, server, realm, poweredBy, certCn, certOrg FROM ports WHERE id = 11").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("HTTP", c.getString(0)); assertEquals("nginx", c.getString(1))
            for (i in 2..7) assertTrue("port column $i must be null", c.isNull(i))
        }
        v2.close()
        // Room's own schema validation on the migrated file, through the production builder.
        val room = NetProbeDb.open(context, MIGRATION_TEST_DB)
        try {
            runBlocking {
                val h = room.hosts().byIdOnce(7)!!
                assertEquals("QA note NAS", h.note); assertEquals(HostStatus.INTERESTING, h.status); assertEquals(DeviceType.NAS, h.deviceType)
                assertNull(h.userType); assertEquals(1, room.ports().forHostOnce(7).size)
            }
        } finally { room.close() }
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }
}
