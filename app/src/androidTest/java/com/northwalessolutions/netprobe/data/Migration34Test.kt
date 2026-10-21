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

/** Schema 3 to 4 on a throwaway file (3C spec 8.2 item 1, Review Focus 1). Never opens, creates or deletes DB_NAME. */
@RunWith(AndroidJUnit4::class)
class Migration34Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    @get:Rule val helper = MigrationTestHelper(instrumentation, NetProbeDb::class.java)

    private data class Stamp(val exists: Boolean, val length: Long, val modified: Long)
    private fun stamp(f: File) = if (f.exists()) Stamp(true, f.length(), f.lastModified()) else Stamp(false, -1, -1)
    private fun deleteTestDb() { listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(MIGRATION_TEST_DB + it).delete() } }

    @Before fun clean() { assertNotEquals(DB_NAME, MIGRATION_TEST_DB); deleteTestDb() }
    @After fun tidy() { deleteTestDb() }

    private fun seedSchema3(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO networks (id, signature, displayName, gatewayIp, prefixLength, ssid, lastSeen, lastRunAt, forgotten) VALUES (1, 'sig', 'Home', '10.0.3.1', 24, 'Home', 5, 4, NULL)")
        db.execSQL("INSERT INTO hosts (id, networkId, ip, mac, reverseDnsName, mdnsName, netbiosName, upnpName, upnpManufacturer, upnpModel, vendor, " +
            "deviceType, customName, identVendor, identModel, identOs, identConfidence, identReasons, snmpSysDescr, httpTitle, mdnsTxtJson, status, note, " +
            "isGateway, firstSeen, lastSeen, lastPortScan, lastDiscoveryRun, macSource, upnpDeviceType, upnpServer, httpServer, httpAuthRealm, " +
            "userType, userVendor, userModel, identSoftware) VALUES " +
            "(7, 1, '10.0.3.59', NULL, 'chnas.lan', 'CHNAS', NULL, NULL, NULL, NULL, NULL, 'NAS', 'DiskStation', 'Synology', 'DS220+', NULL, " +
            "80, '[\"mDNS model DS220+\"]', NULL, NULL, NULL, 'INTERESTING', 'QA note NAS', 0, 1, 2, 3, 4, 4, NULL, NULL, NULL, NULL, 'NAS', 'Synology', 'DS220+', NULL)")
        db.execSQL("INSERT INTO ports (id, hostId, port, serviceName, product, version, detail, confidence, lastSeen) VALUES (11, 7, 5000, 'HTTP', 'nginx', NULL, '200 OK', 'CONFIRMED', 9)")
        db.execSQL("INSERT INTO tool_runs (id, hostId, toolId, port, startedAt, finishedAt, status, optionsJson, summary) " +
            "VALUES (1, 7, 'http-fingerprint', 5000, 100, 200, 'DONE', '{\"tls\":false,\"serverName\":\"chnas.local\"}', 'Synology DSM, 6 findings')")
        db.execSQL("INSERT INTO tool_findings (id, runId, kind, value, detail, extra) VALUES (1, 1, 'FRAMEWORK', 'Synology DSM', 'Matched title', NULL)")
    }

    @Test fun schema3RowsSurviveWithManualOnlyZeroAndRouteNull() {
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        helper.createDatabase(MIGRATION_TEST_DB, 3).use { seedSchema3(it) }
        val v4 = helper.runMigrationsAndValidate(MIGRATION_TEST_DB, 4, true, MIGRATION_3_4)
        v4.query("SELECT customName, status, note, identVendor, userType, userVendor, userModel, manualOnly FROM hosts WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("DiskStation", c.getString(0)); assertEquals("INTERESTING", c.getString(1))
            assertEquals("QA note NAS", c.getString(2)); assertEquals("Synology", c.getString(3)); assertEquals("NAS", c.getString(4))
            assertEquals("Synology", c.getString(5)); assertEquals("DS220+", c.getString(6)); assertEquals(0, c.getInt(7))
        }
        v4.query("SELECT summary, route FROM tool_runs WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("Synology DSM, 6 findings", c.getString(0)); assertTrue("an old run has no route", c.isNull(1))
        }
        v4.query("SELECT COUNT(*) FROM tool_findings WHERE runId = 1").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        v4.query("SELECT COUNT(*) FROM ports WHERE hostId = 7").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        // The new columns work: a hidden manual row and a routed run.
        v4.execSQL("INSERT INTO hosts (networkId, ip, deviceType, identConfidence, identReasons, status, note, isGateway, firstSeen, lastSeen, lastDiscoveryRun, macSource, manualOnly) " +
            "VALUES (1, '10.20.0.5', 'UNKNOWN', 0, '[]', 'UNMARKED', '', 0, 1, 1, 0, 0, 1)")
        v4.execSQL("INSERT INTO tool_runs (hostId, toolId, port, startedAt, finishedAt, status, optionsJson, summary, route) VALUES (7, 'tls-certificate', 443, 300, 301, 'DONE', '{}', 'x', 'OTHER')")
        v4.close()
        val room = NetProbeDb.open(context, MIGRATION_TEST_DB)     // Room's own validation through the production builder
        try {
            assertEquals(2, room.openHelper.readableDatabase.query("SELECT COUNT(*) FROM hosts").use { it.moveToFirst(); it.getInt(0) })
            runBlocking {
                assertEquals(listOf("OTHER", null), room.tools().runsForHost(7).first().map { it.route })   // newest first
                assertEquals("the manual row is hidden", listOf("10.0.3.59"), room.hosts().forNetwork(1).first().map { it.ip })
                assertEquals(2, room.hosts().allForNetwork(1).first().size)
            }
        } finally { room.close() }
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }

    @Test fun oneToFourInOneChain() {
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        helper.createDatabase(MIGRATION_TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO networks (id, signature, displayName, gatewayIp, prefixLength, ssid, lastSeen) VALUES (1, 'sig', 'Home', '10.0.3.1', 24, 'Home', 5)")
            db.execSQL("INSERT INTO hosts (id, networkId, ip, mac, reverseDnsName, mdnsName, netbiosName, upnpName, upnpManufacturer, upnpModel, vendor, " +
                "deviceType, customName, identVendor, identModel, identOs, identConfidence, identReasons, snmpSysDescr, httpTitle, mdnsTxtJson, status, note, " +
                "isGateway, firstSeen, lastSeen, lastPortScan, lastDiscoveryRun, macSource, upnpDeviceType, upnpServer, httpServer, httpAuthRealm) VALUES " +
                "(7, 1, '10.0.3.59', NULL, NULL, 'CHNAS', NULL, NULL, NULL, NULL, NULL, 'NAS', NULL, NULL, NULL, NULL, 0, '[]', NULL, NULL, NULL, 'INTERESTING', 'keep', 0, 1, 2, 3, 4, 4, NULL, NULL, NULL, NULL)")
        }
        val v4 = helper.runMigrationsAndValidate(MIGRATION_TEST_DB, 4, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        v4.query("SELECT note, status, manualOnly FROM hosts WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("keep", c.getString(0)); assertEquals("INTERESTING", c.getString(1)); assertEquals(0, c.getInt(2))
        }
        v4.close()
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }
}
