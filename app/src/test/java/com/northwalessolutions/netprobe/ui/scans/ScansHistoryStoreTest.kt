package com.northwalessolutions.netprobe.ui.scans

import com.northwalessolutions.netprobe.data.AppSettings
import com.northwalessolutions.netprobe.data.FakePrefs
import com.northwalessolutions.netprobe.data.SettingsDefaults
import com.northwalessolutions.netprobe.data.SettingsState
import org.junit.Assert.*
import org.junit.Test

/** Spec 9.1 item 13 and section 7: the cut-off is data, written once per tap, and a settings reset never touches it. */
class ScansHistoryStoreTest {
    @Test fun `set writes the cut-off once, publishes it and survives a new store`() {
        val prefs = FakePrefs(); val store = ScansHistoryStore(prefs)
        assertEquals(0L, store.clearedAt.value)
        store.set(1_234L)
        assertEquals(1, prefs.edits); assertEquals(1_234L, store.clearedAt.value)
        assertEquals(1_234L, ScansHistoryStore(prefs).clearedAt.value)
        store.set(0L); assertEquals("undo restores the previous value", 0L, ScansHistoryStore(prefs).clearedAt.value)
    }
    @Test fun `Reset to defaults leaves the cut-off and the open scan record alone`() {
        val prefs = FakePrefs(); ScansHistoryStore(prefs).set(777L); prefs.stored[com.northwalessolutions.netprobe.engine.portscan.PrefsOpenScanStore.KEY] = "[]"
        val settings = AppSettings(prefs); settings.update { SettingsState(mdns = false, sweepConcurrency = 256) }
        settings.update { SettingsDefaults.reset(it) }
        assertEquals(777L, prefs.getLong("scansClearedAt", -1)); assertEquals("[]", prefs.getString("scansOpenScans", null))
        assertEquals("scansClearedAt", ScansHistoryStore.KEY)
    }
}
