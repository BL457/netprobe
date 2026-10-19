package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

class ReverseDns(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val resolve: (Inet4Address) -> String = { it.canonicalHostName },
) {
    suspend fun lookup(ip: Inet4Address): String? {
        // canonicalHostName ignores thread interrupts, so runInterruptible alone would block past the timeout
        // until the resolver gives up. Run it in a detached, bounded scope and only await it under the timeout.
        val pending = scope.async {
            try {
                runInterruptible { resolve(ip) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        val name = try {
            withTimeoutOrNull(timeoutMs) { pending.await() }
        } finally {
            pending.cancel() // no-op when complete; covers timeout and caller cancellation
        }
        return name?.takeUnless { it.isBlank() || it == ip.hostAddress }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 1000L
        const val MAX_CONCURRENT_LOOKUPS = 8

        @OptIn(ExperimentalCoroutinesApi::class)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_LOOKUPS))
    }
}
