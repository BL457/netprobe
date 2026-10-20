package com.northwalessolutions.netprobe.engine.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Runs a blocking socket call on Dispatchers.IO and closes the socket (or disconnects the connection) from invokeOnCancellation,
 * so Stop returns at once: a blocking read ignores thread interrupts on Android, so runInterruptible alone waits for the socket
 * timeout (Phase 3A spec 2.2.5, Gruff m8). After a cancellation the call's result or exception is dropped.
 */
suspend fun <T> closeOnCancel(close: () -> Unit, block: () -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching(close) }
    Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { cont.resumeWith(runCatching(block)) })
}
