package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.ui.wordlists.WordlistImport
import com.northwalessolutions.netprobe.wordlists.ImportResult
import com.northwalessolutions.netprobe.wordlists.WordlistMeta
import org.junit.Assert.*
import org.junit.Test

/** spec 4.3 and 9: a held row's description suffix; the import result messages. */
class RowTextToolsTest {
    private fun row(running: Boolean, scan: Boolean = false) = HostRow(7, "10.0.3.59", "CHNAS", "Synology DS220+", DeviceType.NAS, 3, HostStatus.UNMARKED,
        false, false, null, running = running, runningIsScan = scan)
    @Test fun `a held row says what holds it, last`() {
        assertEquals("CHNAS, 10.0.3.59, 3 open ports, tool running", RowText.description(row(true), null))
        assertEquals("CHNAS, 10.0.3.59, 3 open ports, port scan running", RowText.description(row(true, scan = true), null))
        assertEquals("CHNAS, 10.0.3.59, 3 open ports", RowText.description(row(false), null))
    }
    @Test fun `every import result has its message`() {
        val meta = WordlistMeta("user:1", "my list", 4614, 1L, false)
        assertEquals("Imported my list, 4614 entries", WordlistImport.message(ImportResult.Imported(meta, 0)))
        assertEquals("Imported my list, 4614 entries (3 skipped)", WordlistImport.message(ImportResult.Imported(meta, 3)))
        assertEquals("That file is too large (limit 10 MB)", WordlistImport.message(ImportResult.TooLarge))
        assertEquals("That file has more than 200000 entries", WordlistImport.message(ImportResult.TooMany))
        assertEquals("That file has no entries", WordlistImport.message(ImportResult.Empty))
        assertEquals("Could not read that file", WordlistImport.message(ImportResult.Unreadable))
    }
}
