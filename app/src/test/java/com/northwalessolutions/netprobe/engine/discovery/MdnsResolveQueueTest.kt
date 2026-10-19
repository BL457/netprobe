package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class MdnsResolveQueueTest {
    private class FakeSource(private val services: Map<String, List<String>>, private val resolveDelayMs: Long = 20) : NsdSource {
        var concurrentResolves = 0; var maxConcurrent = 0; val resolved = mutableListOf<String>()
        override fun discover(type: String, onFound: (NsdFound) -> Unit, onLost: (String) -> Unit): AutoCloseable {
            services[type].orEmpty().forEach { onFound(NsdFound(it, type)) }
            services[type].orEmpty().firstOrNull()?.let { onFound(NsdFound(it, type)) } // duplicate announce
            return AutoCloseable { }
        }
        override suspend fun resolve(found: NsdFound): MdnsRecord? {
            synchronized(this) { concurrentResolves++; maxConcurrent = maxOf(maxConcurrent, concurrentResolves) }
            delay(resolveDelayMs)
            synchronized(this) { concurrentResolves--; resolved += found.name }
            if (found.name == "broken") return null
            return MdnsRecord(InetAddress.getByName("192.168.1.10") as Inet4Address, found.name, found.type, 80, mapOf("model" to "X"), emptyList())
        }
    }

    @Test fun `resolves each found service once, serially`() = runBlocking {
        val src = FakeSource(mapOf("_http._tcp" to listOf("printer", "nas"), "_ssh._tcp" to listOf("nas-ssh", "broken")))
        val out = MdnsResolveQueue(src).run(listOf("_http._tcp", "_ssh._tcp"), durationMs = 200).toList()
        assertEquals(3, out.size)
        assertEquals(4, src.resolved.size)          // broken attempted once, no duplicate from the double announce
        assertEquals(1, src.maxConcurrent)
    }
    @Test fun `a hanging resolve is timed out and the queue continues`() = runBlocking {
        val src = object : NsdSource {
            override fun discover(type: String, onFound: (NsdFound) -> Unit, onLost: (String) -> Unit): AutoCloseable { onFound(NsdFound("hang", type)); onFound(NsdFound("ok", type)); return AutoCloseable { } }
            override suspend fun resolve(found: NsdFound): MdnsRecord? {
                if (found.name == "hang") { delay(60_000); return null }
                return MdnsRecord(InetAddress.getByName("192.168.1.11") as Inet4Address, found.name, found.type, 22, emptyMap(), emptyList())
            }
        }
        val out = MdnsResolveQueue(src, perResolveTimeoutMs = 100).run(listOf("_ssh._tcp"), durationMs = 100).toList()
        assertEquals(listOf("ok"), out.map { it.instanceName })
    }
    @Test fun `a throwing resolve is skipped and the queue continues`() = runBlocking {
        val src = object : NsdSource {
            override fun discover(type: String, onFound: (NsdFound) -> Unit, onLost: (String) -> Unit): AutoCloseable { onFound(NsdFound("boom", type)); onFound(NsdFound("ok", type)); return AutoCloseable { } }
            override suspend fun resolve(found: NsdFound): MdnsRecord? {
                if (found.name == "boom") throw IllegalStateException("nsd failed")
                return MdnsRecord(InetAddress.getByName("192.168.1.12") as Inet4Address, found.name, found.type, 22, emptyMap(), emptyList())
            }
        }
        val out = MdnsResolveQueue(src, perResolveTimeoutMs = 500).run(listOf("_ssh._tcp"), durationMs = 100).toList()
        assertEquals(listOf("ok"), out.map { it.instanceName })
    }
}
