package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class ReverseDnsTest {
    private val ip = InetAddress.getByName("192.0.2.123") as Inet4Address

    private fun stubbornSleep(): String {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { /* ignore interrupts */ }
        }
        return "late.lan"
    }

    @Test fun `loopback resolves or returns null without throwing and respects timeout`() = runBlocking {
        val t0 = System.currentTimeMillis()
        val r = ReverseDns(timeoutMs = 1500).lookup(ip)
        assertTrue(System.currentTimeMillis() - t0 < 2500)
        if (r != null) assertNotEquals("192.0.2.123", r)
    }

    @Test fun `interrupt-ignoring resolver returns null within timeout`() = runBlocking {
        val t0 = System.currentTimeMillis()
        assertNull(ReverseDns(timeoutMs = 300, resolve = { stubbornSleep() }).lookup(ip))
        assertTrue(System.currentTimeMillis() - t0 < 300 + 500)
    }

    @Test fun `resolver returning the ip string yields null`() = runBlocking {
        assertNull(ReverseDns(resolve = { it.hostAddress!! }).lookup(ip))
    }

    @Test fun `resolver returning a name yields it`() = runBlocking {
        assertEquals("host.lan", ReverseDns(resolve = { "host.lan" }).lookup(ip))
    }

    @Test fun `caller cancellation propagates promptly`() = runBlocking {
        val job = async(Dispatchers.Default) { ReverseDns(timeoutMs = 10_000, resolve = { stubbornSleep() }).lookup(ip) }
        delay(200)
        val t0 = System.currentTimeMillis()
        job.cancel()
        val err = try { job.await(); null } catch (e: CancellationException) { e }
        assertNotNull(err)
        assertTrue(System.currentTimeMillis() - t0 < 500)
    }
}
