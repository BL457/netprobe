package com.northwalessolutions.netprobe.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runs [run]'s block until it has succeeded once; concurrent callers wait for it; a failure is retried by the next caller. */
class PurgeOnce {
    private val mutex = Mutex()
    private var done = false
    suspend fun run(block: suspend () -> Unit) {
        mutex.withLock {
            if (done) return@withLock
            block()
            done = true
        }
    }
}
