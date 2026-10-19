package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import java.net.Inet4Address

open class Sweep(
    private val prober: HostProber,
    private val concurrency: Int = 128,
    private val dispatcherFactory: (Int) -> CoroutineDispatcher = { Dispatchers.IO.limitedParallelism(it) },
) {
    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress

    open fun run(hosts: Sequence<Inet4Address>): Flow<Liveness> = channelFlow {
        val list = hosts.toList()
        var probed = 0
        _progress.value = 0 to list.size
        val dispatcher = dispatcherFactory(concurrency)
        val sem = Semaphore(concurrency)
        coroutineScope {
            for (ip in list) {
                sem.acquire()
                launch(dispatcher) {
                    try {
                        val l = prober.probe(ip)
                        if (l.alive) send(l)
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { /* a single host failing never stops the sweep */
                    } finally {
                        synchronized(this@Sweep) { probed++; _progress.value = probed to list.size }
                        sem.release()
                    }
                }
            }
        }
    }
}
