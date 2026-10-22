package com.northwalessolutions.netprobe.oui

import com.northwalessolutions.netprobe.engine.tools.AppMessages
import com.northwalessolutions.netprobe.ui.wordlists.WordlistImport
import com.northwalessolutions.netprobe.wordlists.FetchFailure
import com.northwalessolutions.netprobe.wordlists.ImportResult
import com.northwalessolutions.netprobe.wordlists.UrlImportResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URL

class OuiUpdaterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val parent = Job()
    private val scope = CoroutineScope(Dispatchers.Default + parent)
    @After fun end() { parent.cancel() }

    private class Meta : OuiMeta { var info: OuiInfo? = null; override fun read() = info; override fun write(info: OuiInfo) { this.info = info } }
    private val header = "Registry,Assignment,Organization Name,Organization Address\n"
    private val files = mapOf(
        "https://standards-oui.ieee.org/oui/oui.csv" to header + "MA-L,001122,\"Acme, Inc.\",x\n",
        "https://standards-oui.ieee.org/oui28/mam.csv" to header + "MA-M,70B3D5A,Midsize Ltd,x\n",
        "https://standards-oui.ieee.org/oui36/oui36.csv" to header + "MA-S,70B3D5A1B,Tiny GmbH,x\n",
    )
    private fun fetched(url: URL) = UrlImportResult.Fetched(files.getValue(url.toString()).toByteArray())

    /** A store already holding one old vendor (00:aa:bb:cc.. = "Old Vendor"), info (5, 1). */
    private fun seeded(): Triple<OuiStore, Meta, AppMessages> {
        val m = Meta(); val s = OuiStore(File(tmp.newFolder(), "oui.bin"), m)
        s.replace(OuiTable.Builder().also { it.add(24, 0x00AABB, "Old Vendor") }.build(), 5L)
        return Triple(s, m, AppMessages())
    }
    private fun waitIdle(u: OuiUpdater) = runBlocking { withTimeout(10_000) { while (u.state.value != OuiUpdateState.Idle) delay(5) } }

    @Test fun `three files are fetched in order, progress names each, and the table is written`() {
        val (s, m, msgs) = seeded()
        val urls = mutableListOf<String>(); val steps = mutableListOf<OuiUpdateState>(); val caps = mutableListOf<Long>()
        lateinit var u: OuiUpdater
        u = OuiUpdater(scope, s, msgs, { url, max, _ -> urls += url.toString(); caps += max; steps += u.state.value; fetched(url) }, now = { 1234L })
        u.attach(); assertTrue(u.start()); waitIdle(u)
        assertEquals(OuiSources.ALL, urls); assertEquals(listOf(8L * 1024 * 1024), caps.distinct())
        assertEquals(listOf(OuiUpdateState.Running(1, 3), OuiUpdateState.Running(2, 3), OuiUpdateState.Running(3, 3)), steps)
        assertEquals("Vendor database updated: 3 vendors", u.result.value)
        assertEquals(OuiInfo(1234L, 3), m.info)
        assertEquals("Tiny GmbH", s.lookup("70:b3:d5:a1:b2:c3")); assertNull("the old table is replaced", s.lookup("00:aa:bb:cc:00:01"))
        assertNull(msgs.current.value)
    }
    @Test fun `a failing file leaves the old table untouched and reports the fetcher text`() {
        val (s, m, msgs) = seeded()
        val u = OuiUpdater(scope, s, msgs, { url, _, _ -> if (url.toString().contains("mam")) UrlImportResult.Failed(FetchFailure.NoAnswer) else fetched(url) })
        u.attach(); assertTrue(u.start()); waitIdle(u)
        assertEquals(WordlistImport.failure(FetchFailure.NoAnswer), u.result.value)
        assertEquals(OuiInfo(5L, 1), m.info); assertEquals("Old Vendor", s.lookup("00:aa:bb:cc:00:01"))
    }
    @Test fun `a file over the cap and a file with no entries have their own words`() {
        val (s, _, msgs) = seeded()
        val big = OuiUpdater(scope, s, msgs, { _, _, _ -> UrlImportResult.Stored(ImportResult.TooLarge) })
        big.attach(); assertTrue(big.start()); waitIdle(big); assertEquals(OuiCopy.TOO_LARGE, big.result.value)
        val bad = OuiUpdater(scope, s, msgs, { _, _, _ -> UrlImportResult.Fetched("<html>no csv here</html>".toByteArray()) })
        bad.attach(); assertTrue(bad.start()); waitIdle(bad); assertEquals(OuiCopy.EMPTY, bad.result.value)
    }
    @Test fun `stopping mid-download leaves the old table and records nothing`() {
        val (s, m, msgs) = seeded()
        val inSecond = CompletableDeferred<Unit>()
        val u = OuiUpdater(scope, s, msgs, { url, _, _ ->
            if (url.toString().contains("mam")) { inSecond.complete(Unit); CompletableDeferred<Unit>().await() }   // never completes; Stop cancels it
            fetched(url)
        })
        u.attach(); assertTrue(u.start())
        runBlocking { withTimeout(10_000) { inSecond.await() } }
        u.cancel(); waitIdle(u)
        assertEquals(OuiCopy.CANCELLED, u.result.value)
        assertEquals(OuiInfo(5L, 1), m.info); assertEquals("Old Vendor", s.lookup("00:aa:bb:cc:00:01"))
    }
    @Test fun `a second start while one runs is refused and an unattached result goes to the app messages`() {
        val (s, _, msgs) = seeded()
        val gate = CompletableDeferred<Unit>()
        val u = OuiUpdater(scope, s, msgs, { url, _, _ -> gate.await(); fetched(url) })
        assertTrue(u.start()); assertFalse(u.start()); gate.complete(Unit); waitIdle(u)
        runBlocking { withTimeout(10_000) { while (msgs.current.value == null) delay(5) } }
        assertEquals("Vendor database updated: 3 vendors", msgs.current.value?.text); assertNull(u.result.value)
    }
}
