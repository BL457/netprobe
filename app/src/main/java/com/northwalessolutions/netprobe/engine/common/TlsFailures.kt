package com.northwalessolutions.netprobe.engine.common

import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/** Cause-walking classification of a TLS exchange failure; TLS providers wrap timeouts and resets in an SSLException. */
object TlsFailures {
    private const val MAX_CAUSE_DEPTH = 6
    private val RESET_WORDS = listOf("reset", "terminated the handshake", "connection closed", "broken pipe")

    private fun chain(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH)

    /** A read timeout can arrive bare or wrapped, depending on the TLS provider. */
    fun isTimeout(e: Throwable): Boolean = chain(e).any { it is SocketTimeoutException }

    /** True when the exception or a cause is an SSLException: the peer spoke, but not TLS we could finish (a plain reset is not). */
    fun isSslFailure(e: Throwable): Boolean = chain(e).any { it is SSLException }

    /** The peer dropped the connection (reset, early close, EOF) rather than answering with a TLS alert. */
    fun isReset(e: Throwable): Boolean = chain(e).any { t ->
        (t is SocketException && t !is SocketTimeoutException) || t is EOFException ||
            t.message?.let { m -> RESET_WORDS.any { m.contains(it, ignoreCase = true) } } == true
    }
}
