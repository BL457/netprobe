package com.northwalessolutions.netprobe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.neigh.NeighbourTable
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/** Spec section 11 item 6. Pass: at least one MAC for a swept host, no ERR. Run on the Pixel 7 Pro. */
@RunWith(AndroidJUnit4::class)
class NeighbourSpikeTest {
    @Test fun neighbourDumpReturnsMacsAfterSweep() = runBlocking {
        val before = NeighbourTable.dump()
        Log.i("NeighbourSpike", "before sweep: $before")
        assertTrue("dump denied: ${before.exceptionOrNull()}", before.isSuccess)

        // Touch 254 hosts of the common home /24s so the kernel ARPs for them.
        val prefixes = listOf("10.0.3.", "192.168.1.", "192.168.0.", "10.0.0.")
        withContext(Dispatchers.IO.limitedParallelism(128)) {
            prefixes.flatMap { p -> (1..254).map { "$p$it" } }.map { ip ->
                async { runCatching { Socket().use { it.connect(InetSocketAddress(ip, 80), 300) } } }
            }.awaitAll()
        }
        delay(500)
        val after = NeighbourTable.dump().getOrThrow()
        after.forEach { Log.i("NeighbourSpike", "${it.ip} ${it.mac} ${it.state}") }
        Log.i("NeighbourSpike", "entries with mac: ${after.size}")
        assertTrue("expected at least one neighbour with a MAC, got ${after.size}", after.isNotEmpty())
    }
}
