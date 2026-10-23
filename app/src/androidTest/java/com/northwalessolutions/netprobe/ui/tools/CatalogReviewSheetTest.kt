package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.script.CatalogEntry
import com.northwalessolutions.netprobe.engine.script.CatalogFailure
import com.northwalessolutions.netprobe.engine.script.CatalogItem
import com.northwalessolutions.netprobe.engine.script.CatalogPlan
import com.northwalessolutions.netprobe.engine.script.ImportPlan
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import com.northwalessolutions.netprobe.engine.tools.ToolDescriptor
import com.northwalessolutions.netprobe.engine.tools.ToolIcon
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The catalog review sheet and its dialogs, drawn from fixed state. Written to compile with the unit gate; not run by the batch. */
@RunWith(AndroidJUnit4::class)
class CatalogReviewSheetTest {
    @get:Rule val rule = createComposeRule()

    private fun item(id: String, version: String, old: String? = null, network: NetworkScope = NetworkScope.LAN, broadcast: Boolean = false,
                     widened: Boolean = false, broadcastNew: Boolean = false, local: Boolean = false): CatalogItem {
        val d = ToolDescriptor("user:$id", "Tool $id", "Does a thing.", ToolIcon.BOLT, ToolCategory.NETWORK, TargetKind.HOST, emptyList(), network, version, null, broadcast = broadcast)
        val entry = CatalogEntry(id, "$id.js", "Tool $id", version, "a".repeat(64), network, "Does a thing.", broadcast)
        return CatalogItem(entry, ImportPlan(d, "tool = {};", "a".repeat(64), null, "$id.js", null, true), old, NetworkScope.TARGET.takeIf { old != null }, widened, broadcastNew, local)
    }

    private fun review(plan: CatalogPlan, acknowledged: Boolean = false) = CatalogState.Review(plan, acknowledged)

    @Test fun threeListsAndTheUnchangedCount() {
        val plan = CatalogPlan(listOf(item("tool-a", "1.0", broadcast = true)), listOf(item("tool-b", "1.1", old = "1.0", local = true)),
            listOf(CatalogFailure("Tool c", "Could not reach the server")), unchanged = 4)
        rule.setContent { NetProbeTheme { CatalogReviewSheet(review(plan), {}, {}, {}) } }
        rule.onNodeWithTag("catalogNew:tool-a").assertIsDisplayed()
        rule.onNodeWithTag("catalogBroadcast:tool-a").assertIsDisplayed()
        rule.onNodeWithTag("catalogUpdated:tool-b").assertIsDisplayed()
        rule.onNodeWithTag("catalogReplacesCopy:tool-b").assertIsDisplayed()
        rule.onNodeWithTag("catalogFailed:0").assertIsDisplayed()
        rule.onNodeWithTag("catalogUnchanged").assertIsDisplayed()
    }

    @Test fun aWideningUpdateNeedsTheYesBeforeInstall() {
        var acknowledged = false
        val plan = CatalogPlan(emptyList(), listOf(item("tool-b", "1.1", old = "1.0", network = NetworkScope.ANY, widened = true)), emptyList(), 0)
        rule.setContent { NetProbeTheme { CatalogReviewSheet(review(plan, acknowledged), { acknowledged = it }, {}, {}) } }
        rule.onNodeWithTag("catalogWidened:tool-b").assertIsDisplayed()
        rule.onNodeWithTag("catalogInstall").assertIsNotEnabled()
        rule.onNodeWithTag("catalogAck").performClick()
        assertEquals(true, acknowledged)
    }

    @Test fun installAndCancelStayPinnedAndFire() {
        var installed = 0; var cancelled = 0
        val plan = CatalogPlan(listOf(item("tool-a", "1.0")), emptyList(), emptyList(), 0)
        rule.setContent { NetProbeTheme { CatalogReviewSheet(review(plan), {}, { installed++ }, { cancelled++ }) } }
        rule.onNodeWithTag("catalogInstall").assertIsEnabled().performClick()
        rule.onNodeWithTag("catalogCancel").performClick()
        assertEquals(listOf(1, 1), listOf(installed, cancelled))
    }

    @Test fun anUpToDateCatalogCannotInstall() {
        rule.setContent { NetProbeTheme { CatalogReviewSheet(review(CatalogPlan(emptyList(), emptyList(), emptyList(), 3)), {}, {}, {}) } }
        rule.onNodeWithTag("catalogNothing").assertIsDisplayed()
        rule.onNodeWithTag("catalogInstall").assertIsNotEnabled()
    }

    @Test fun theErrorDialogOffersRetry() {
        var retried = 0
        rule.setContent { NetProbeTheme { CatalogFailedDialog("Could not reach the server", { retried++ }, {}) } }
        rule.onNodeWithTag("catalogRetry").performClick()
        assertEquals(1, retried)
    }
}
