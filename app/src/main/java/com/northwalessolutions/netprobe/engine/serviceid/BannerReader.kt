package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.common.Probe
import kotlinx.coroutines.runInterruptible
import java.net.InetAddress

class BannerReader(val timeoutMs: Int = 1000, val maxBytes: Int = 1024) {
    /** Connects, optionally sends [sendFirst], reads up to maxBytes or until timeout. Null when nothing readable. */
    suspend fun read(ip: InetAddress, port: Int, sendFirst: ByteArray? = null): ByteArray? {
        val o = Probe.connect(ip, port, timeoutMs, keepOpen = true) as? Probe.Outcome.Connected ?: return null
        val socket = o.socket ?: return null
        return try {
            runInterruptible {
                socket.soTimeout = timeoutMs
                sendFirst?.let { socket.getOutputStream().write(it); socket.getOutputStream().flush() }
                val buf = ByteArray(maxBytes); var n = 0
                val input = socket.getInputStream()
                val deadline = System.currentTimeMillis() + timeoutMs
                try {
                    while (n < maxBytes && System.currentTimeMillis() < deadline) {
                        val r = input.read(buf, n, maxBytes - n)
                        if (r < 0) break
                        n += r
                        if (sendFirst == null && n > 0 && buf.copyOf(n).contains('\n'.code.toByte())) break
                    }
                } catch (_: java.net.SocketTimeoutException) { }
                if (n == 0) null else buf.copyOf(n)
            }
        } catch (e: InterruptedException) { throw kotlinx.coroutines.CancellationException()
        } catch (_: Exception) { null
        } finally { runCatching { socket.close() } }
    }
}
