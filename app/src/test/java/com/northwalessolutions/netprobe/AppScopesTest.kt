package com.northwalessolutions.netprobe

import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Test

/** spec 9.3 test 6 on the JVM: a failure in a delete or restore is logged and the app scope survives. */
class AppScopesTest {
    @Test fun `a failing background task is logged and the scope keeps working`() = runBlocking {
        val seen = CompletableDeferred<Throwable>()
        val scope = AppScopes.create(AppScopes.handler { seen.complete(it) })
        scope.launch { throw IllegalStateException("UNIQUE constraint failed: hosts.id") }
        assertEquals("UNIQUE constraint failed: hosts.id", withTimeout(1_000) { seen.await() }.message)
        assertEquals(42, scope.async { 42 }.await())   // SupervisorJob: one failure never cancels the scope
        scope.cancel()
    }
}
