package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

class ReverseDns(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    suspend fun lookup(ip: Inet4Address): String? {
        // canonicalHostName ignores thread interrupts, so runInterruptible alone would block past the timeout
        // until the resolver gives up. Run it in a detached scope and only await it under the timeout.
        val pending = scope.async {
            try {
                runInterruptible(Dispatchers.IO) { ip.canonicalHostName }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        val name = withTimeoutOrNull(timeoutMs) { pending.await() }
        if (name == null) pending.cancel()
        return name?.takeUnless { it.isBlank() || it == ip.hostAddress }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 1000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
