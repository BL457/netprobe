package com.northwalessolutions.netprobe.engine.tools.smb

import com.northwalessolutions.netprobe.engine.common.CancellableIo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException

/** One SMB2 request and its single reply over one connection; null when no reply arrives. */
interface SmbTransport {
    suspend fun exchange(request: ByteArray): ByteArray?
    fun close()
}

/** Direct TCP transport framing (MS-SMB2 2.1): a zero byte and a 24-bit big-endian length before each message; replies capped at 64 KB. */
object SmbFraming {
    const val MAX_MESSAGE = 65_536
    private const val FRAME_HEADER = 4
    fun frame(message: ByteArray): ByteArray {
        val n = message.size
        return byteArrayOf(0, (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()) + message
    }
    fun readFrame(input: InputStream): ByteArray? = readFrame(input) {}
    /** As [readFrame], calling [beforeRead] before every read, so a caller can shrink the socket timeout to an exchange deadline. */
    fun readFrame(input: InputStream, beforeRead: () -> Unit): ByteArray? {
        val head = readFully(input, FRAME_HEADER, beforeRead) ?: return null
        if (head[0].toInt() != 0) return null
        val length = ((head[1].toInt() and 0xff) shl 16) or ((head[2].toInt() and 0xff) shl 8) or (head[3].toInt() and 0xff)
        if (length > MAX_MESSAGE) return null
        return readFully(input, length, beforeRead)
    }
    private fun readFully(input: InputStream, n: Int, beforeRead: () -> Unit): ByteArray? {
        val out = ByteArray(n); var got = 0
        while (got < n) { beforeRead(); val r = input.read(out, got, n - got); if (r < 0) return null; got += r }
        return out
    }
}

/**
 * A connected TCP socket from Probe.connect; every exchange runs inside CancellableIo.closing, so Stop ends a blocked read at once.
 * [timeoutMs] bounds the whole exchange (spec 6.3), not each read: before every read the socket timeout is cut to the time left, so a
 * server dripping one byte at a time cannot stretch an exchange. The coroutine timeout is the backstop for a blocked write: it closes
 * the socket. Either way the exchange gives null and the connection is finished.
 */
class TcpSmbTransport(private val socket: Socket, private val timeoutMs: Int) : SmbTransport {
    override suspend fun exchange(request: ByteArray): ByteArray? {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MS
        fun remainingMs(): Int = ((deadline - System.nanoTime()) / NANOS_PER_MS).toInt()
        return try {
            withTimeoutOrNull(timeoutMs.toLong()) {
                CancellableIo.closing(socket) {
                    socket.soTimeout = timeoutMs
                    socket.getOutputStream().apply { write(SmbFraming.frame(request)); flush() }
                    SmbFraming.readFrame(socket.getInputStream()) {
                        val left = remainingMs()
                        if (left <= 0) throw SocketTimeoutException("SMB exchange deadline")
                        socket.soTimeout = left
                    }
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: IOException) { null }
    }
    override fun close() { runCatching { socket.close() } }
    private companion object { const val NANOS_PER_MS = 1_000_000L }
}
