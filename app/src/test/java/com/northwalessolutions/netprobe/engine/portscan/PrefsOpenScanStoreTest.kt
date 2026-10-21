package com.northwalessolutions.netprobe.engine.portscan

import com.northwalessolutions.netprobe.data.FakePrefs
import org.junit.Assert.*
import org.junit.Test

/** Spec 3.3, process death: the record of scans that were running. */
class PrefsOpenScanStoreTest {
    @Test fun `add replaces a host's entry, remove deletes it and drain returns everything once`() {
        val prefs = FakePrefs(); val store = PrefsOpenScanStore(prefs)
        store.add(OpenScan(1, 10, 100)); store.add(OpenScan(2, 10, 200)); store.add(OpenScan(1, 10, 300))
        assertEquals(listOf(OpenScan(2, 10, 200), OpenScan(1, 10, 300)), PrefsOpenScanStore(prefs).drain().also { assertTrue(prefs.stored.isEmpty()) })
        assertTrue("drained once", store.drain().isEmpty())
        store.add(OpenScan(3, 10, 1)); store.remove(3)
        assertFalse("the key is removed when the list is empty", prefs.stored.containsKey(PrefsOpenScanStore.KEY))
    }
    @Test fun `a damaged record is dropped and the next add starts fresh`() {
        val prefs = FakePrefs(); prefs.stored[PrefsOpenScanStore.KEY] = "{not json"
        val store = PrefsOpenScanStore(prefs)
        assertTrue(store.drain().isEmpty())
        prefs.stored[PrefsOpenScanStore.KEY] = "[1,2]"
        store.add(OpenScan(4, 1, 2))
        assertEquals(listOf(OpenScan(4, 1, 2)), store.drain())
    }
    @Test fun `the key is the documented one`() {
        assertEquals("scansOpenScans", PrefsOpenScanStore.KEY)
    }
}
