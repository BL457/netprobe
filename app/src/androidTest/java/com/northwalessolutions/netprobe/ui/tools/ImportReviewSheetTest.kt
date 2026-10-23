package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.script.ImportPlan
import com.northwalessolutions.netprobe.engine.script.ReplacePlan
import com.northwalessolutions.netprobe.engine.tools.InputSpec
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

/** The confirmation sheet and the import dialogs, drawn from fixed state. Written to compile with the unit gate; run on a device in B5. */
@RunWith(AndroidJUnit4::class)
class ImportReviewSheetTest {
    @get:Rule val rule = createComposeRule()

    private fun plan(network: NetworkScope = NetworkScope.TARGET, replace: ReplacePlan? = null) = ImportPlan(
        ToolDescriptor("user:my-tool", "My tool", "Does a thing.", ToolIcon.TERMINAL, ToolCategory.MINE, TargetKind.NONE,
            listOf(InputSpec.Text("path", "Path", required = false)), network, "1.1", "Ann"),
        "tool = {};\nline two", "a".repeat(64), null, "t.js", replace)

    @Test fun theSheetShowsTheFactsAndTheAuthorAsUnverified() {
        rule.setContent { NetProbeTheme { ImportReviewSheet(ImportState.Review(plan(NetworkScope.ANY)), {}, {}, {}) } }
        rule.onNodeWithText("My tool 1.1").assertIsDisplayed()
        rule.onNodeWithText("Ann (unverified)").assertIsDisplayed()
        rule.onNodeWithTag("importScopeCallout").assertIsDisplayed()
        rule.onNodeWithText("Path, text, optional").assertIsDisplayed()
        rule.onNodeWithTag("importSha").assertIsDisplayed()
    }

    @Test fun viewSourceOpensTheScript() {
        rule.setContent { NetProbeTheme { ImportReviewSheet(ImportState.Review(plan()), {}, {}, {}) } }
        rule.onNodeWithTag("importSource").assertDoesNotExist()
        rule.onNodeWithTag("importViewSource").performClick()
        rule.onNodeWithTag("importSource").assertIsDisplayed()
    }

    @Test fun aWideningReplaceNeedsTheYesBeforeImport() {
        var acknowledged = false
        val widening = ReplacePlan("My tool", "1.0", "1.1", NetworkScope.TARGET, NetworkScope.ANY, true, "b".repeat(64), "a".repeat(64), emptyList(), emptyList(), emptyList(), true)
        rule.setContent {
            NetProbeTheme { ImportReviewSheet(ImportState.Review(plan(NetworkScope.ANY, widening), acknowledged = acknowledged), { acknowledged = it }, {}, {}) }
        }
        rule.onNodeWithTag("diffWidened").assertIsDisplayed()
        rule.onNodeWithTag("importConfirm").assertIsNotEnabled()
        rule.onNodeWithTag("importAck").performClick()
        assertEquals(true, acknowledged)
    }

    @Test fun importAndCancelFire() {
        var imported = 0; var cancelled = 0
        rule.setContent { NetProbeTheme { ImportReviewSheet(ImportState.Review(plan()), {}, { imported++ }, { cancelled++ }) } }
        rule.onNodeWithTag("importConfirm").assertIsEnabled().performClick()
        rule.onNodeWithTag("importCancel").performClick()
        assertEquals(listOf(1, 1), listOf(imported, cancelled))
    }

    @Test fun everyProblemShowsOnItsOwnLine() {
        rule.setContent { NetProbeTheme { ImportFailedDialog(listOf("One", "Two", "Three"), {}, {}) } }
        rule.onNodeWithTag("importError:0").assertIsDisplayed()
        rule.onNodeWithTag("importError:2").assertIsDisplayed()
        rule.onNodeWithText("3 problems").assertIsDisplayed()
    }
}
