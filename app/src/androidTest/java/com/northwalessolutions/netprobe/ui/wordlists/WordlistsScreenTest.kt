package com.northwalessolutions.netprobe.ui.wordlists

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import com.northwalessolutions.netprobe.wordlists.WordlistMeta
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL

/** spec 12.2 item 6. */
@RunWith(AndroidJUnit4::class)
class WordlistsScreenTest {
    @get:Rule val rule = createComposeRule()
    private val builtIns = listOf(WordlistMeta("builtin:common-short", "common-short", 298, null, true), WordlistMeta("builtin:admin-panels", "admin-panels", 153, null, true))
    private val mine = WordlistMeta("user:1", "my list", 4614, 0L, false)
    private val deleted = mutableListOf<WordlistMeta>(); private var imports = 0
    private fun show(lists: List<WordlistMeta>, importing: Boolean = false, loading: Boolean = false) = rule.setContent {
        NetProbeTheme { WordlistsContent(lists, importing, null, {}, WordlistsActions(onImport = { imports++ }, onDelete = { deleted += it }), formatDate = { "3 Jan 2026" }, loading = loading) }
    }

    @Test fun builtInRowsHaveNoDelete() {
        show(builtIns)
        rule.onNodeWithText("298 entries, built in").assertIsDisplayed()
        rule.onAllNodesWithText("Delete").assertCountEquals(0)
        rule.onNodeWithText("No imported wordlists yet").assertIsDisplayed()
    }
    @Test fun anImportedRowConfirmsBeforeDelete() {
        show(builtIns + mine)
        rule.onNodeWithText("4614 entries, imported 3 Jan 2026").assertIsDisplayed()
        rule.onNodeWithTag("delete:user:1").assert(hasContentDescription("Delete my list")).performClick()
        assertTrue(deleted.isEmpty())
        rule.onNodeWithText("Delete my list?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick(); assertTrue(deleted.isEmpty())
        rule.onNodeWithTag("delete:user:1").performClick(); rule.onNodeWithTag("confirmDelete").performClick()
        assertEquals(listOf(mine), deleted)
    }
    @Test fun theImportPillIsOutsideTheScrollingList() {
        show(builtIns + (1..40).map { WordlistMeta("user:$it", "list $it", it, 0L, false) })
        rule.onNodeWithTag("wordlistList").performScrollToIndex(40)
        rule.onNodeWithTag("importWordlist").assertIsDisplayed().performClick(); assertEquals(1, imports)
        val pill = rule.onNodeWithTag("importWordlist").fetchSemanticsNode(); val list = rule.onNodeWithTag("wordlistList").fetchSemanticsNode()
        assertFalse(generateSequence(pill.parent) { it.parent }.any { it.id == list.id })
    }
    @Test fun whileImportingThePillReadsImportingAndIsDisabled() {
        show(builtIns, importing = true)
        rule.onNodeWithText("Importing").assertIsDisplayed(); rule.onNodeWithTag("importWordlist").assertIsNotEnabled()
    }
    @Test fun whileLoadingItShowsAPlaceholderNotTheEmptyStateAndImportIsDisabled() {
        show(emptyList(), loading = true)
        rule.onNodeWithText("Loading wordlists").assertIsDisplayed()
        rule.onAllNodesWithText("No imported wordlists yet").assertCountEquals(0)
        rule.onNodeWithTag("importWordlist").assertIsNotEnabled()
    }
    private val urls = mutableListOf<URL>(); private var cancels = 0
    private fun showUrl(urlState: UrlImportState = UrlImportState.Idle, message: String? = null) = rule.setContent {
        NetProbeTheme { WordlistsContent(builtIns, false, message, {}, WordlistsActions(onImport = { imports++ }, onImportUrl = { urls += it }, onCancelUrl = { cancels++ }),
            formatDate = { "3 Jan 2026" }, urlState = urlState) }
    }
    @Test fun bothImportPillsArePinnedOutsideTheList() {   // 3C spec 8.2 item 8
        showUrl()
        val list = rule.onNodeWithTag("wordlistList").fetchSemanticsNode()
        for (tag in listOf("importWordlist", "importUrl")) {
            val pill = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            assertFalse(tag, generateSequence(pill.parent) { it.parent }.any { it.id == list.id })
        }
        rule.onNodeWithText("Import file").assertIsDisplayed(); rule.onNodeWithText("Import from URL").assertIsDisplayed()
    }
    @Test fun theUrlDialogValidatesAndImports() {
        showUrl()
        rule.onNodeWithTag("importUrl").performClick()
        rule.onNodeWithTag("urlField").performTextInput("javascript:alert(1)")
        rule.onNodeWithText("Enter a web address starting with https:// or http://").assertIsDisplayed()
        rule.onNodeWithTag("urlImport").assertIsNotEnabled()
        rule.onNodeWithTag("urlField").performTextClearance()
        rule.onNodeWithTag("urlField").performTextInput("example.com/list.txt")
        rule.onNodeWithTag("urlImport").assertIsEnabled().performClick()
        assertEquals(listOf("https://example.com/list.txt"), urls.map { it.toString() })
        rule.onNodeWithTag("urlField").assertDoesNotExist()   // the dialog closed
    }
    @Test fun whileFetchingThePillShowsProgressAndCancelIsPresent() {
        showUrl(UrlImportState.Fetching(1_258_291, 3_565_158))
        rule.onNodeWithText("Fetching 1.2 of 3.4 MB").assertIsDisplayed()
        rule.onNodeWithTag("importWordlist").assertIsNotEnabled(); rule.onNodeWithTag("importUrl").assertIsNotEnabled()
        rule.onNodeWithTag("cancelUrl").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f)).performClick()
        assertEquals(1, cancels)
    }
    @Test fun cancelShowsImportCancelled() {
        showUrl(message = "Import cancelled")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Import cancelled").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("cancelUrl").assertDoesNotExist()
    }
}
