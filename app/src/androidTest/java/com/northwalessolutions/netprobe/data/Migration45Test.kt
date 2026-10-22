package com.northwalessolutions.netprobe.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Schema 4 to 5 on a throwaway file (Phase 3E spec 7 and 10). Never opens, creates or deletes DB_NAME. */
@RunWith(AndroidJUnit4::class)
class Migration45Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    @get:Rule val helper = MigrationTestHelper(instrumentation, NetProbeDb::class.java)

    private data class Stamp(val exists: Boolean, val length: Long, val modified: Long)
    private fun stamp(f: File) = if (f.exists()) Stamp(true, f.length(), f.lastModified()) else Stamp(false, -1, -1)
    private fun deleteTestDb() { listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(MIGRATION_TEST_DB + it).delete() } }

    @Before fun clean() { assertNotEquals(DB_NAME, MIGRATION_TEST_DB); deleteTestDb() }
    @After fun tidy() { deleteTestDb() }

    private fun seedSchema4(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO networks (id, signature, displayName, gatewayIp, prefixLength, ssid, lastSeen, lastRunAt, forgotten) VALUES (1, 'sig', 'Home', '10.0.3.1', 24, 'Home', 5, 4, NULL)")
        db.execSQL("INSERT INTO hosts (id, networkId, ip, deviceType, identConfidence, identReasons, status, note, isGateway, firstSeen, lastSeen, lastDiscoveryRun, macSource, manualOnly, customName) " +
            "VALUES (7, 1, '10.0.3.59', 'NAS', 80, '[]', 'INTERESTING', 'QA note NAS', 0, 1, 2, 4, 4, 0, 'DiskStation')")
        db.execSQL("INSERT INTO ports (id, hostId, port, serviceName, product, version, detail, confidence, lastSeen, title, server, realm, poweredBy, certCn, certOrg) " +
            "VALUES (11, 7, 5000, 'HTTP', 'nginx', '1.25.3', '200 OK', 'CONFIRMED', 9, 'DiskStation', 'nginx/1.25.3', 'DSM', 'PHP/8.2', 'nas.lan', 'Synology Inc.')")
        db.execSQL("INSERT INTO ports (id, hostId, port, serviceName, product, version, detail, confidence, lastSeen) VALUES (12, 7, 22, 'SSH', 'OpenSSH', '9.6p1', NULL, 'CONFIRMED', 9)")
        db.execSQL("INSERT INTO tool_runs (id, hostId, toolId, port, startedAt, finishedAt, status, optionsJson, summary, route) " +
            "VALUES (1, 7, 'http-fingerprint', 5000, 100, 200, 'DONE', '{}', 'Synology DSM, 6 findings', 'WIFI')")
    }

    @Test fun schema4RowsSurviveWithTheFourNewColumnsNull() {
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        helper.createDatabase(MIGRATION_TEST_DB, 4).use { seedSchema4(it) }
        val v5 = helper.runMigrationsAndValidate(MIGRATION_TEST_DB, 5, true, MIGRATION_4_5)
        v5.query("SELECT product, version, detail, confidence, title, server, realm, poweredBy, certCn, certOrg, stack, stackRole, cpe, probe FROM ports WHERE id = 11").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("nginx", c.getString(0)); assertEquals("1.25.3", c.getString(1)); assertEquals("200 OK", c.getString(2)); assertEquals("CONFIRMED", c.getString(3))
            assertEquals("DiskStation", c.getString(4)); assertEquals("nginx/1.25.3", c.getString(5)); assertEquals("DSM", c.getString(6)); assertEquals("PHP/8.2", c.getString(7))
            assertEquals("nas.lan", c.getString(8)); assertEquals("Synology Inc.", c.getString(9))
            for (i in 10..13) assertTrue("an old row has no stack field $i", c.isNull(i))
        }
        v5.query("SELECT COUNT(*) FROM ports WHERE hostId = 7").use { c -> assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0)) }
        v5.query("SELECT customName, note, status FROM hosts WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("DiskStation", c.getString(0)); assertEquals("QA note NAS", c.getString(1)); assertEquals("INTERESTING", c.getString(2))
        }
        v5.query("SELECT summary, route FROM tool_runs WHERE id = 1").use { c -> assertTrue(c.moveToFirst()); assertEquals("Synology DSM, 6 findings", c.getString(0)); assertEquals("WIFI", c.getString(1)) }
        // The new columns work.
        v5.execSQL("UPDATE ports SET stack = 'Synology DSM', stackRole = 'device', cpe = 'nginx', probe = 'synology-title' WHERE id = 11")
        v5.close()
        val room = NetProbeDb.open(context, MIGRATION_TEST_DB)     // Room's own validation through the production builder
        try {
            runBlocking {
                val ports = room.ports().forHostOnce(7).associateBy { it.port }
                assertEquals("Synology DSM", ports.getValue(5000).stack); assertEquals("device", ports.getValue(5000).stackRole)
                assertEquals("nginx", ports.getValue(5000).cpe); assertEquals("synology-title", ports.getValue(5000).probe)
                assertNull(ports.getValue(22).stack); assertEquals("OpenSSH", ports.getValue(22).product)
                assertEquals(1, room.hosts().forNetwork(1).first().size)
            }
        } finally { room.close() }
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }

    @Test fun oneToFiveInOneChain() {
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        helper.createDatabase(MIGRATION_TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO networks (id, signature, displayName, gatewayIp, prefixLength, ssid, lastSeen) VALUES (1, 'sig', 'Home', '10.0.3.1', 24, 'Home', 5)")
            db.execSQL("INSERT INTO hosts (id, networkId, ip, mac, reverseDnsName, mdnsName, netbiosName, upnpName, upnpManufacturer, upnpModel, vendor, " +
                "deviceType, customName, identVendor, identModel, identOs, identConfidence, identReasons, snmpSysDescr, httpTitle, mdnsTxtJson, status, note, " +
                "isGateway, firstSeen, lastSeen, lastPortScan, lastDiscoveryRun, macSource, upnpDeviceType, upnpServer, httpServer, httpAuthRealm) VALUES " +
                "(7, 1, '10.0.3.59', NULL, NULL, 'CHNAS', NULL, NULL, NULL, NULL, NULL, 'NAS', NULL, NULL, NULL, NULL, 0, '[]', NULL, NULL, NULL, 'INTERESTING', 'keep', 0, 1, 2, 3, 4, 4, NULL, NULL, NULL, NULL)")
            db.execSQL("INSERT INTO ports (id, hostId, port, serviceName, product, version, detail, confidence, lastSeen) VALUES (11, 7, 5000, 'HTTP', 'nginx', NULL, '200 OK', 'CONFIRMED', 9)")
        }
        val v5 = helper.runMigrationsAndValidate(MIGRATION_TEST_DB, 5, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        v5.query("SELECT note, status, manualOnly FROM hosts WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("keep", c.getString(0)); assertEquals("INTERESTING", c.getString(1)); assertEquals(0, c.getInt(2))
        }
        v5.query("SELECT product, stack, probe FROM ports WHERE id = 11").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("nginx", c.getString(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2))
        }
        v5.close()
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }
}
