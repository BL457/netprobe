package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.BindFailedException
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
    open val progress: StateFlow<Pair<Int, Int>> get() = _progress

    /** Alive hosts only (the MVP contract, used for the local range). */
    open fun run(hosts: Sequence<Inet4Address>): Flow<Liveness> = runAll(hosts).filter { it.alive }

    /** Every probe result, alive or dead, so the sweep guard counts probes and answers from one ordered stream (extended spec 4.7). */
    open fun runAll(hosts: Sequence<Inet4Address>): Flow<Liveness> = channelFlow {
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
                        send(prober.probe(ip))
                    } catch (e: CancellationException) { throw e
                    } catch (e: BindFailedException) { throw e      // the network cannot be used: end the sweep, do not count a dead host
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
