package com.northwalessolutions.netprobe.engine.serviceid

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.northwalessolutions.netprobe.engine.portscan.PortTable
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * 3E spec 10: the real [SocketTransport] through [ServiceIdentifier] against server sockets in this process that play three recorded
 * replies (Redis INFO, an SSH banner, an HTTP 200 with a Server header). Loopback only; no database is opened.
 */
@RunWith(AndroidJUnit4::class)
class ServiceProbesDeviceTest {
    private val servers = mutableListOf<ServerSocket>()

    private fun serve(handler: (Socket) -> Unit): Int {
        val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).also { servers += it }
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (e: Exception) { return@thread }
                thread(isDaemon = true) { try { c.soTimeout = 3000; handler(c) } catch (e: Exception) { } finally { try { c.close() } catch (e: Exception) { } } }
            }
        }
        return ss.localPort
    }
    /** Reads until [marker] arrives or the peer closes or goes quiet. */
    private fun readUntil(c: Socket, marker: String): String {
        val sb = StringBuilder(); val buf = ByteArray(512)
        try {
            while (!sb.contains(marker)) { val n = c.getInputStream().read(buf); if (n < 0) break; sb.append(String(buf, 0, n, Charsets.ISO_8859_1)) }
        } catch (e: SocketTimeoutException) { }
        return sb.toString()
    }
    private fun write(c: Socket, s: String) { c.getOutputStream().write(s.toByteArray(Charsets.ISO_8859_1)); c.getOutputStream().flush() }

    @After fun closeServers() { servers.forEach { try { it.close() } catch (e: Exception) { } } }

    @Test fun theRealTransportNamesRedisSshAndHttpFromRecordedReplies() = runBlocking {
        val redis = serve { c -> if (readUntil(c, "INFO").contains("INFO")) write(c, "\$122\r\n# Server\r\nredis_version:7.2.4\r\nredis_git_sha1:00000000\r\n") }
        val ssh = serve { c -> write(c, "SSH-2.0-OpenSSH_9.6p1 Debian-3\r\n"); Thread.sleep(300) }
        val http = serve { c ->
            if (readUntil(c, "\r\n\r\n").isNotEmpty())
                write(c, "HTTP/1.1 200 OK\r\nServer: nginx/1.25.3\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n<html><head><title>Hello</title></head></html>")
        }
        val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val raw = ctx.resources.openRawResource(ctx.resources.getIdentifier("service_probes", "raw", ctx.packageName)).bufferedReader().readText()
        val probes = ServiceProbeRules.load(raw).probes
        val table = PortTable.fromJson("""[{"port":22,"name":"SSH"},{"port":80,"name":"HTTP"},{"port":6379,"name":"Redis"}]""")
        val identifier = ServiceIdentifier(table, ServiceProbes(probes), portOverride = mapOf(6379 to redis, 22 to ssh, 80 to http))
        val ip = InetAddress.getByName("127.0.0.1")
        val mode = ProbeMode.Active(ActiveProbeBudget())

        val r = identifier.identify(ip, 6379, mode = mode, hostId = 1)
        assertEquals("Redis", r.name); assertEquals("7.2.4", r.version)
        val s = identifier.identify(ip, 22, mode = mode, hostId = 1)
        assertEquals("SSH", s.name); assertEquals("OpenSSH", s.product); assertEquals("9.6p1", s.version)
        val h = identifier.identify(ip, 80, mode = mode, hostId = 1)
        assertEquals("HTTP", h.name); assertEquals("nginx", h.product); assertNotNull(h.server)
    }
}
