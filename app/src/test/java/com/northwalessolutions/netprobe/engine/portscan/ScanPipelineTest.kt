package com.northwalessolutions.netprobe.engine.portscan

import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 3D spec 3.3 (final review S3, S4): the foreground gate pauses the sweep and the identifies, and the wiring that supplies it stays in place. Loopback only. */
class ScanPipelineTest {
    private val servers = mutableListOf<ServerSocket>()
    private fun listen(): Int { val s = ServerSocket(0, 50, InetAddress.getLoopbackAddress()); servers += s; return s.localPort }
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }

    private val gateOpen = MutableStateFlow(true)
    private val gate: suspend () -> Unit = { gateOpen.first { it } }
    private val info = ServiceInfo("fake", confidence = Confidence.PORT_TABLE)
    private val counts = AtomicReference(IdentifyCount())
    private val sink = ScanSink({ }, { change -> counts.updateAndGet { change(it) } })

    @Test fun `a closed gate from the start probes nothing and identifies nothing`() = runBlocking {
        val identified = AtomicInteger()
        val pipeline = ScanPipeline(PortScanEngine(), gate, ScanOptions(), { identified.incrementAndGet(); info }, { _, _ -> })
        gateOpen.value = false
        val job = launch(Dispatchers.Default) { pipeline.run(InetAddress.getLoopbackAddress(), ScanProfile.Custom(listOf(listen(), listen())), sink) }
        delay(300)
        assertEquals(0, identified.get())
        assertEquals(IdentifyCount(), counts.get())
        gateOpen.value = true; job.join()
        assertEquals(2, identified.get())
    }

    @Test fun `identifies already queued do not start while the gate is closed`() = runBlocking {
        val ports = listOf(listen(), listen(), listen())
        val started = CopyOnWriteArrayList<Int>()
        val firstRunning = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val pipeline = ScanPipeline(PortScanEngine(), gate, ScanOptions(), { port ->
            started += port
            if (started.size == 1) { firstRunning.complete(Unit); release.await() }
            info
        }, { _, _ -> }, maxParallelIdentifies = 1)
        val job = launch(Dispatchers.Default) { pipeline.run(InetAddress.getLoopbackAddress(), ScanProfile.Custom(ports), sink) }
        withTimeout(WAIT_MS) { firstRunning.await() }
        withTimeout(WAIT_MS) { while (counts.get().pending < ports.size) delay(10) }     // all three are found and queued
        gateOpen.value = false                                                           // the app goes to the background
        release.complete(Unit)
        delay(400)
        assertEquals("no banner connection starts in the background", 1, started.size)
        gateOpen.value = true; job.join()
        assertEquals(3, started.size)
        assertEquals(IdentifyCount(0, 3), counts.get())
    }

    @Test fun `the wiring that gives a port scan its gate is in place`() {
        assertTrue("HostScanBody must hand the foreground gate to the pipeline", sourceOf("engine/portscan/HostScanBody.kt").contains("gate = c.foregroundGate::await"))
        assertTrue("the gate must follow the same lifecycle flow tool runs use", sourceOf("AppContainer.kt").contains("ForegroundGate(foreground)"))
    }

    private fun sourceOf(path: String): String =
        listOf("src/main/java", "app/src/main/java").map { File("$it/com/northwalessolutions/netprobe/$path") }.first { it.exists() }.readText()

    private companion object { const val WAIT_MS = 5_000L }
}
