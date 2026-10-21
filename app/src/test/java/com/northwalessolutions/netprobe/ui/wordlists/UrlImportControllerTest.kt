package com.northwalessolutions.netprobe.ui.wordlists

import com.northwalessolutions.netprobe.engine.tools.AppMessages
import com.northwalessolutions.netprobe.wordlists.FetchFailure
import com.northwalessolutions.netprobe.wordlists.UrlImportResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.URL

/** 3C spec 5.1: one URL import at a time, on the app scope, with its result going to the visible screen or to AppMessages. */
@OptIn(ExperimentalCoroutinesApi::class)
class UrlImportControllerTest {
    private val url = URL("https://example.com/list.txt")

    @Test fun `a fetch shows progress, refuses a second start, and gives the attached screen its message`() = runTest {
        val messages = AppMessages(); val gate = CompletableDeferred<Unit>()
        val c = UrlImportController(backgroundScope, { _, progress -> progress(65_536, 200_000); gate.await(); UrlImportResult.Failed(FetchFailure.Status(404)) }, messages)
        c.attach()
        assertTrue(c.start(url)); runCurrent()
        assertEquals(UrlImportState.Fetching(65_536, 200_000), c.state.value)
        assertFalse("one URL import at a time", c.start(URL("https://example.com/other.txt")))
        gate.complete(Unit); runCurrent()
        assertEquals(UrlImportState.Idle, c.state.value)
        assertEquals("Could not fetch: the server answered 404", c.message.value); assertNull(messages.current.value)
        c.consumeMessage(); assertNull(c.message.value)
    }
    @Test fun `with the screen gone the result goes to AppMessages`() = runTest {
        val messages = AppMessages()
        val c = UrlImportController(backgroundScope, { _, _ -> UrlImportResult.Failed(FetchFailure.UnknownHost) }, messages)
        c.attach(); c.detach()
        c.start(url); runCurrent()
        assertNull(c.message.value); assertEquals("Could not fetch: unknown host", messages.current.value?.text)
    }
    @Test fun `cancel ends the job and says Import cancelled`() = runTest {
        val c = UrlImportController(backgroundScope, { _, _ -> awaitCancellation() }, AppMessages())
        c.attach(); c.start(url); runCurrent()
        assertTrue(c.state.value is UrlImportState.Fetching)
        c.cancel(); runCurrent()
        assertEquals(UrlImportState.Idle, c.state.value); assertEquals("Import cancelled", c.message.value)
        assertTrue("a new import may start", c.start(url))
    }
    @Test fun `a cancelled import still finishing blocks a new start and keeps the state`() = runTest {   // plan review G10
        val gate = CompletableDeferred<Unit>()
        val c = UrlImportController(backgroundScope, { _, _ ->
            withContext(NonCancellable) { gate.await() }      // the store's commit, which Cancel never interrupts
            currentCoroutineContext().ensureActive()
            UrlImportResult.Cancelled
        }, AppMessages())
        c.attach(); c.start(url); runCurrent()
        c.cancel(); runCurrent()
        assertFalse("the first import has not completed", c.start(url))
        assertTrue("both pills stay disabled", c.state.value is UrlImportState.Fetching)
        gate.complete(Unit); runCurrent()
        assertEquals(UrlImportState.Idle, c.state.value); assertEquals("Import cancelled", c.message.value)
        assertTrue("now a new import may start", c.start(url))
    }
    @Test fun `an unexpected failure is logged and reported as a connection failure`() = runTest {
        val logged = mutableListOf<Throwable>()
        val c = UrlImportController(backgroundScope, { _, _ -> throw IllegalStateException("boom") }, AppMessages(), log = { logged += it })
        c.attach(); c.start(url); runCurrent()
        assertEquals("Could not fetch: connection failed", c.message.value); assertEquals(1, logged.size)
    }
}
