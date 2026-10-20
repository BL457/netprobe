package com.northwalessolutions.netprobe.engine.tools.dirbrute

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A resizable concurrency limiter (spec 5.3, Gruff M3): starts at C, halves on a counted Timeout or Reset at most once per window,
 * and recovers by one per RECOVERY_SUCCESSES consecutive successes, never above C. After a halving no permit is granted until the
 * running count falls below the new limit, so effective concurrency shrinks without interrupting requests in flight.
 */
class AdaptiveLimiter(private val c: Int, private val now: () -> Long = System::currentTimeMillis, private val windowMs: Long = 3_000) {
    private val mutex = Mutex()
    private var limit = c
    private var active = 0
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()
    private var consecutive = 0
    private var lastShrink = Long.MIN_VALUE / 2
    val limitNow: Int get() = limit

    suspend fun acquire() {
        val wait = mutex.withLock { if (active < limit) { active++; null } else CompletableDeferred<Unit>().also { waiters.addLast(it) } } ?: return
        try {
            wait.await()
        } catch (ce: CancellationException) {
            withContext(NonCancellable) { mutex.withLock { if (!waiters.remove(wait)) { active--; admit() } } }   // already admitted: hand the permit back
            throw ce
        }
    }
    /** NonCancellable, as in [acquire]: a cancelled worker always hands its permit back, even when the lock is contended. */
    suspend fun release() { withContext(NonCancellable) { mutex.withLock { active--; admit() } } }
    suspend fun onSuccess() {
        mutex.withLock {
            consecutive++
            if (consecutive >= RECOVERY_SUCCESSES) { consecutive = 0; if (limit < c) { limit++; admit() } }
        }
    }
    suspend fun onTimeoutOrReset() {
        mutex.withLock {
            consecutive = 0
            val t = now()
            if (t - lastShrink >= windowMs) { lastShrink = t; limit = maxOf(1, limit / 2) }
        }
    }
    /** Called under the lock: admit waiters while there is room under the current limit. */
    private fun admit() { while (active < limit && waiters.isNotEmpty()) { active++; waiters.removeFirst().complete(Unit) } }
    companion object { const val RECOVERY_SUCCESSES = 20 }
}
