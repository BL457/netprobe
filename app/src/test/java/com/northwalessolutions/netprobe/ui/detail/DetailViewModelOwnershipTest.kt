package com.northwalessolutions.netprobe.ui.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Spec 9.1 item 9 (design lock). DetailViewModel needs an AppContainer, which needs a Context, so the lifetime itself is proved in
 * PortScansTest `a scan outlives the scope that asked for it`; this test keeps the view model from taking ownership back.
 */
class DetailViewModelOwnershipTest {
    private val source: String = listOf("src/main/java", "app/src/main/java").map { File("$it/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt") }
        .first { it.exists() }.readText()

    @Test fun `the port scan is not owned by the view model`() {
        assertFalse("a ScanController in the view model stops the scan with the screen", source.contains("ScanController("))
        assertFalse("the engine is driven by HostScanBody now", source.contains("portScanEngine"))
        assertFalse(source.contains("viewModelScope, env"))
    }
    @Test fun `scans start, stop and are joined through PortScans`() {
        assertTrue(source.contains("c.portScans.start("))
        assertTrue(source.contains("c.portScans.stop(hostId)"))
        assertTrue("Remove device must join the scan before the delete (spec 5.3 step 1)", source.contains("c.portScans.stopAndJoin(hostId)"))
    }
    @Test fun `the Remove menu leaves the stop to stopAndJoin so no message is posted for a deleted device`() {
        val content = listOf("src/main/java", "app/src/main/java").map { File("$it/com/northwalessolutions/netprobe/ui/detail/DetailContent.kt") }
            .first { it.exists() }.readText()
        val remove = content.lines().single { it.contains("onRemove = {") && it.contains("DetailMenu(") }
        assertFalse("a non-silent stop races stopAndJoin's silent flag (final review S7)", remove.contains("onStop"))
    }
}
