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
}
