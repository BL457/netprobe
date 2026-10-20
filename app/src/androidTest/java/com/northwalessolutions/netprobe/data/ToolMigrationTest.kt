package com.northwalessolutions.netprobe.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Schema 2 to 3 on a throwaway file; additive migration (spec 7.3, Review Focus 5). Never opens, creates or deletes DB_NAME. */
@RunWith(AndroidJUnit4::class)
class ToolMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    @get:Rule val helper = MigrationTestHelper(instrumentation, NetProbeDb::class.java)

    private data class Stamp(val exists: Boolean, val length: Long, val modified: Long)
    private fun stamp(f: File) = if (f.exists()) Stamp(true, f.length(), f.lastModified()) else Stamp(false, -1, -1)
    private fun deleteTestDb() { listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(MIGRATION_TEST_DB + it).delete() } }

    @Before fun clean() { assertNotEquals(DB_NAME, MIGRATION_TEST_DB); deleteTestDb() }
    @After fun tidy() { deleteTestDb() }

    private fun seedSchema2(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO networks (id, signature, displayName, gatewayIp, prefixLength, ssid, lastSeen) VALUES (1, 'sig', 'Home', '10.0.3.1', 24, 'Home', 5)")
        db.execSQL("INSERT INTO hosts (id, networkId, ip, mac, reverseDnsName, mdnsName, netbiosName, upnpName, upnpManufacturer, upnpModel, vendor, " +
            "deviceType, customName, identVendor, identModel, identOs, identConfidence, identReasons, snmpSysDescr, httpTitle, mdnsTxtJson, status, note, " +
            "isGateway, firstSeen, lastSeen, lastPortScan, lastDiscoveryRun, macSource, upnpDeviceType, upnpServer, httpServer, httpAuthRealm, " +
            "userType, userVendor, userModel, identSoftware) VALUES " +
            "(7, 1, '10.0.3.59', NULL, 'chnas.lan', 'CHNAS', NULL, NULL, NULL, NULL, NULL, 'NAS', 'DiskStation', 'Synology', 'DS220+', NULL, " +
            "80, '[\"mDNS model DS220+\"]', NULL, NULL, NULL, 'INTERESTING', 'QA note NAS', 0, 1, 2, 3, 4, 4, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)")
        db.execSQL("INSERT INTO ports (id, hostId, port, serviceName, product, version, detail, confidence, lastSeen) VALUES (11, 7, 5000, 'HTTP', 'nginx', NULL, '200 OK', 'CONFIRMED', 9)")
    }

    @Test fun schema2RowsSurviveAndTheNewTablesWork() {
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        helper.createDatabase(MIGRATION_TEST_DB, 2).use { seedSchema2(it) }
        val v3 = helper.runMigrationsAndValidate(MIGRATION_TEST_DB, 3, true, MIGRATION_2_3)
        v3.query("SELECT customName, status, note, identVendor FROM hosts WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("DiskStation", c.getString(0)); assertEquals("INTERESTING", c.getString(1))
            assertEquals("QA note NAS", c.getString(2)); assertEquals("Synology", c.getString(3))
        }
        v3.execSQL("INSERT INTO tool_runs (id, hostId, toolId, port, startedAt, finishedAt, status, optionsJson, summary) " +
            "VALUES (1, 7, 'dir-bruteforce', 5000, 100, 200, 'DONE', '{}', '7 found in 4614 paths')")
        v3.execSQL("INSERT INTO tool_findings (id, runId, kind, value, detail, extra) VALUES (1, 1, 'PATH', '/admin', '200  1.2 KB', NULL)")
        v3.query("SELECT COUNT(*) FROM tool_findings WHERE runId = 1").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        v3.execSQL("PRAGMA foreign_keys = ON")
        v3.execSQL("DELETE FROM hosts WHERE id = 7")
        v3.query("SELECT COUNT(*) FROM tool_runs").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        v3.query("SELECT COUNT(*) FROM tool_findings").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        v3.close()
        val room = NetProbeDb.open(context, MIGRATION_TEST_DB)
        try { runBlocking { assertNotNull(room.networks().byId(1)) } } finally { room.close() }
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }

    @Test fun oneToThreeInOneChain() {
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        helper.createDatabase(MIGRATION_TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO networks (id, signature, displayName, gatewayIp, prefixLength, ssid, lastSeen) VALUES (1, 'sig', 'Home', '10.0.3.1', 24, 'Home', 5)")
            db.execSQL("INSERT INTO hosts (id, networkId, ip, mac, reverseDnsName, mdnsName, netbiosName, upnpName, upnpManufacturer, upnpModel, vendor, " +
                "deviceType, customName, identVendor, identModel, identOs, identConfidence, identReasons, snmpSysDescr, httpTitle, mdnsTxtJson, status, note, " +
                "isGateway, firstSeen, lastSeen, lastPortScan, lastDiscoveryRun, macSource, upnpDeviceType, upnpServer, httpServer, httpAuthRealm) VALUES " +
                "(7, 1, '10.0.3.59', NULL, NULL, 'CHNAS', NULL, NULL, NULL, NULL, NULL, 'NAS', NULL, NULL, NULL, NULL, 0, '[]', NULL, NULL, NULL, 'INTERESTING', 'keep', 0, 1, 2, 3, 4, 4, NULL, NULL, NULL, NULL)")
        }
        val v3 = helper.runMigrationsAndValidate(MIGRATION_TEST_DB, 3, true, MIGRATION_1_2, MIGRATION_2_3)
        v3.query("SELECT note, userType, identSoftware FROM hosts WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("keep", c.getString(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2))
        }
        v3.close()
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }
}
