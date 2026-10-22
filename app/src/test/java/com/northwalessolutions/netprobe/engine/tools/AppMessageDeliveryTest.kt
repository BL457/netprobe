package com.northwalessolutions.netprobe.engine.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The scan-finished popup came back on every Detail visit: a message shown once must not reach a second screen. */
class AppMessageDeliveryTest {
    /** One screen's life: it takes whatever is current, starts showing it (the snackbar stays up until [hold] completes), then is torn down. */
    private fun visit(messages: AppMessages, shown: MutableList<String>, hold: CompletableDeferred<Unit>?): Job {
        val screen = Job()
        val scope = CoroutineScope(Dispatchers.Unconfined + screen)
        scope.launch {
            val m = messages.current.filterNotNull().first()
            AppMessageDelivery.consume(scope, m, messages::dismiss) { text -> shown += text; hold?.await() }
        }
        return screen
    }

    @Test fun `a message whose snackbar was cut short is not shown again on the next screen`() = runBlocking {
        val messages = AppMessages()
        messages.post("Scan finished")
        val shown = mutableListOf<String>()
        val first = visit(messages, shown, CompletableDeferred())      // the snackbar never finishes: the Director leaves first
        first.cancelAndJoin()
        val second = visit(messages, shown, null)
        second.cancelAndJoin()
        assertEquals(listOf("Scan finished"), shown)
        assertNull(messages.current.value)
    }

    @Test fun `a message posted while a screen is visible is still delivered to it`() = runBlocking {
        val messages = AppMessages()
        val shown = mutableListOf<String>()
        val screen = visit(messages, shown, null)
        messages.post("Scan finished")
        screen.cancelAndJoin()
        assertEquals(listOf("Scan finished"), shown)
    }
}
