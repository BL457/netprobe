package com.northwalessolutions.netprobe.engine.vulns

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RateLimiter(private val permits: Int, private val windowMs: Long, private val clock: () -> Long = System::currentTimeMillis,
                  private val sleeper: suspend (Long) -> Unit = { delay(it) }) {
    private val stamps = ArrayDeque<Long>(); private val mutex = Mutex()
    suspend fun acquire() = mutex.withLock {
        while (true) {
            val now = clock()
            while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) stamps.removeFirst()
            if (stamps.size < permits) { stamps.addLast(now); return@withLock }
            sleeper(windowMs - (now - stamps.first()))
        }
    }
}
