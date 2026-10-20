package com.northwalessolutions.netprobe.engine.common

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a blocking socket call with runInterruptible and NO dispatcher argument, so it inherits the caller's dispatcher as
 * Probe.connect does (spec 4.6, Gruff M7). A watcher coroutine closes the socket when the caller is cancelled, so a blocked read
 * ends at once, not at its timeout. Putting Dispatchers.IO here would funnel every tool read onto the shared 64-thread pool.
 * (The watcher uses Dispatchers.Unconfined only so the close runs on the cancelling thread; it does no I/O.)
 */
object CancellableIo {
    suspend fun <T> closing(socket: Closeable, block: () -> T): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!finished.get()) runCatching { socket.close() } }
        }
        try { runInterruptible { block() } } finally { finished.set(true); watcher.cancel() }
    }
}
