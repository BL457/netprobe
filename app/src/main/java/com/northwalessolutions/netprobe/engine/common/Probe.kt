package com.northwalessolutions.netprobe.engine.common

import kotlinx.coroutines.*
import java.net.*

object Probe {
    sealed class Outcome {
        data class Connected(val rttMs: Int, val socket: Socket?) : Outcome()
        object Refused : Outcome(); object Timeout : Outcome(); object Unreachable : Outcome()
    }

    private fun classifyConnect(e: ConnectException): Outcome {
        val m = e.message.orEmpty()
        return when {
            m.contains("ECONNREFUSED", true) || m.contains("refused", true) -> Outcome.Refused
            m.contains("EHOSTUNREACH", true) || m.contains("ENETUNREACH", true) -> Outcome.Unreachable
            else -> Outcome.Timeout
        }
    }

    /**
     * Blocking connect made cancellable: a watcher coroutine closes the socket when the caller is cancelled,
     * which unblocks connect() (the interrupt alone does not).
     * Must be called from a background dispatcher; the engines call it from their limitedParallelism view.
     * Do not put Dispatchers.IO inside this function: that would funnel the 128-wide scan onto the 64-thread IO pool.
     */
    suspend fun connect(ip: InetAddress, port: Int, timeoutMs: Int, keepOpen: Boolean = false): Outcome = coroutineScope {
        val socket = Socket()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        // A blocking connect is not interruptible; close() from another coroutine unblocks it (spec 5.3).
        val closer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!finished.get()) runCatching { socket.close() } }
        }
        try {
            runInterruptible {
                val t0 = System.nanoTime()
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                val rtt = ((System.nanoTime() - t0) / 1_000_000).toInt()
                if (keepOpen) Outcome.Connected(rtt, socket) else { socket.close(); Outcome.Connected(rtt, null) }
            }
        } catch (e: SocketTimeoutException) { socket.close(); Outcome.Timeout
        } catch (e: NoRouteToHostException) { socket.close(); Outcome.Unreachable
        } catch (e: ConnectException) { socket.close(); classifyConnect(e) }
        catch (e: Exception) { socket.close(); Outcome.Timeout }
        finally { finished.set(true); closer.cancel() }
    }
}
