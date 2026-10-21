package com.northwalessolutions.netprobe.scans

import com.northwalessolutions.netprobe.engine.tools.AppMessages
import com.northwalessolutions.netprobe.ui.wordlists.UrlImportController
import com.northwalessolutions.netprobe.ui.wordlists.UrlImportState
import com.northwalessolutions.netprobe.wordlists.FetchFailure
import com.northwalessolutions.netprobe.wordlists.ImportResult
import com.northwalessolutions.netprobe.wordlists.UrlImportResult
import com.northwalessolutions.netprobe.wordlists.WordlistMeta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.URL

/** Spec 9.1 item 11. */
@OptIn(ExperimentalCoroutinesApi::class)
class UrlImportLiveWorkTest {
    private val url = URL("https://raw.githubusercontent.com/x/list.txt")
    private fun TestScope.rig(fetch: suspend (URL, (Long, Long?) -> Unit) -> UrlImportResult): Pair<UrlImportController, LiveWork> {
        val lw = LiveWork(backgroundScope, clock = { testScheduler.currentTime })
        return UrlImportController(backgroundScope, fetch, AppMessages(), liveWork = lw) to lw
    }

    @Test fun `start begins an entry labelled with the URL host and progress bytes reach it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (c, lw) = rig { _, progress -> progress(65_536, 200_000); gate.await(); UrlImportResult.Failed(FetchFailure.UnknownHost) }
        c.attach(); c.start(url); runCurrent()
        val live = lw.live.value.single()
        assertEquals(LiveWork.Kind.WORDLIST_IMPORT, live.kind); assertEquals("raw.githubusercontent.com", live.label)
        advanceTimeBy(LiveWork.PUBLISH_MS); runCurrent()
        assertEquals(65_536, lw.live.value.single().done); assertEquals(200_000, lw.live.value.single().total)
        gate.complete(Unit); runCurrent()
    }
    @Test fun `Stored ends DONE with the store's own message`() = runTest {
        val meta = WordlistMeta("custom:1", "list", 12, 1L, false)
        val (c, lw) = rig { _, _ -> UrlImportResult.Stored(ImportResult.Imported(meta, 0)) }
        c.attach(); c.start(url); runCurrent()
        val e = lw.ended.value.single()
        assertEquals(LiveWork.Outcome.DONE, e.outcome); assertEquals("Imported list, 12 entries", e.summary)
        assertTrue(lw.live.value.isEmpty())
    }
    @Test fun `Failed ends FAILED with the fetch message`() = runTest {
        val (c, lw) = rig { _, _ -> UrlImportResult.Failed(FetchFailure.Status(404)) }
        c.attach(); c.start(url); runCurrent()
        val e = lw.ended.value.single()
        assertEquals(LiveWork.Outcome.FAILED, e.outcome); assertEquals("Could not fetch: the server answered 404", e.summary)
    }
    @Test fun `a Cancelled result ends STOPPED with Import cancelled`() = runTest {
        val (c, lw) = rig { _, _ -> UrlImportResult.Cancelled }
        c.attach(); c.start(url); runCurrent()
        val e = lw.ended.value.single()
        assertEquals(LiveWork.Outcome.STOPPED, e.outcome); assertEquals("Import cancelled", e.summary)
    }
    @Test fun `stopping the entry from Scans cancels the fetch`() = runTest {
        val (c, lw) = rig { _, _ -> awaitCancellation() }
        c.attach(); c.start(url); runCurrent()
        lw.stop(lw.live.value.single().id); runCurrent()
        assertEquals(UrlImportState.Idle, c.state.value); assertEquals("Import cancelled", c.message.value)
        val e = lw.ended.value.single()
        assertEquals(LiveWork.Outcome.STOPPED, e.outcome); assertEquals("Import cancelled", e.summary)
        assertEquals(0, lw.heldStops)
    }
    @Test fun `without a LiveWork the controller behaves as before`() = runTest {
        val c = UrlImportController(backgroundScope, { _, _ -> UrlImportResult.Failed(FetchFailure.UnknownHost) }, AppMessages())
        c.attach(); c.start(url); runCurrent()
        assertEquals("Could not fetch: unknown host", c.message.value)
    }
}
