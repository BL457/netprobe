package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.common.closeOnCancel
import kotlinx.coroutines.CancellationException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Open so the probe-ladder tests can record every byte sent (Task 5). */
open class BannerReader(val timeoutMs: Int = 1000, val maxBytes: Int = 1024) {
    /** Connects, optionally sends [sendFirst], reads up to maxBytes or until timeout. Null when nothing readable. Stop closes the socket at once. */
    open suspend fun read(ip: InetAddress, port: Int, sendFirst: ByteArray? = null): ByteArray? {
        val o = Probe.connect(ip, port, timeoutMs, keepOpen = true) as? Probe.Outcome.Connected ?: return null
        val socket = o.socket ?: return null
        return try {
            closeOnCancel({ socket.close() }) { readBlocking(socket, sendFirst) }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { null
        } finally { runCatching { socket.close() } }
    }

    private fun readBlocking(socket: Socket, sendFirst: ByteArray?): ByteArray? {
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
        } catch (_: SocketTimeoutException) { }
        return if (n == 0) null else buf.copyOf(n)
    }
}
