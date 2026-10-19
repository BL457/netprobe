package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.net.Inet4Address

data class MdnsRecord(val ip: Inet4Address, val instanceName: String, val type: String, val port: Int,
                      val txt: Map<String, String>, val ipv6: List<String>)

/** Android NSD reports types as "._googlecast._tcp" (and sometimes with a trailing dot); rules and evidence use "_googlecast._tcp". */
object MdnsType { fun normalise(type: String): String = type.trim('.') }

/** A discovered-but-unresolved service. [handle] carries the platform object (the NsdServiceInfo from onServiceFound). */
data class NsdFound(val name: String, val type: String, val handle: Any? = null)

interface NsdSource {
    fun discover(type: String, onFound: (NsdFound) -> Unit, onLost: (String) -> Unit): AutoCloseable
    suspend fun resolve(found: NsdFound): MdnsRecord?
}

class MdnsResolveQueue(private val source: NsdSource, private val perResolveTimeoutMs: Long = 3000) {
    private companion object { const val DRAIN_GRACE_MS = 2000L }

    fun run(types: List<String>, durationMs: Long): Flow<MdnsRecord> = channelFlow {
        val queue = Channel<NsdFound>(Channel.UNLIMITED)
        val seen = HashSet<Pair<String, String>>()
        val handles = types.map { t ->
            source.discover(t, onFound = { f -> if (synchronized(seen) { seen.add(f.name to f.type) }) queue.trySend(f) }, onLost = { })
        }
        val resolver = launch {
            for (found in queue) {
                // runCatching would swallow CancellationException (including the timeout's), so catch explicitly.
                val rec = withTimeoutOrNull(perResolveTimeoutMs) {
                    try { source.resolve(found) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                }
                if (rec != null) send(rec.copy(type = MdnsType.normalise(rec.type)))
            }
        }
        try {
            delay(durationMs)
        } finally {
            handles.forEach { runCatching { it.close() } }
            queue.close()
        }
        withTimeoutOrNull(DRAIN_GRACE_MS) { resolver.join() } ?: resolver.cancel()
    }
}
