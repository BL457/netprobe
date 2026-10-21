package com.northwalessolutions.netprobe.wordlists

import com.northwalessolutions.netprobe.engine.common.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wordlist import from a URL (3C spec 5.2, 5.3). HttpURLConnection, not the LAN HttpClient: platform DNS, the system trust store with
 * hostname checks, streaming, and the default network (never bound to Wi-Fi; it may be cellular). Redirects are followed here, never
 * by the platform, so every hop passes UrlImportRules.redirect. No Authorization and no Cookie are ever sent; the app installs no
 * CookieHandler. The only code that fetches a URL the Director types.
 */
class UrlWordlistFetcher(private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
                         private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
                         private val readTimeoutMs: Int = URL_READ_TIMEOUT_MS) {

    suspend fun fetch(url: URL, store: WordlistStore, onProgress: (Long, Long?) -> Unit = { _, _ -> }): UrlImportResult =
        withContext(Dispatchers.IO) { follow(url, store, onProgress) }

    private sealed interface Step {
        data class Redirect(val url: URL) : Step
        data class Done(val result: UrlImportResult) : Step
    }

    private suspend fun follow(original: URL, store: WordlistStore, onProgress: (Long, Long?) -> Unit): UrlImportResult {
        var current = original
        var hops = 0
        while (true) {
            when (val step = step(original, current, hops, store, onProgress)) {
                is Step.Redirect -> {
                    // A followed hop to a name that resolves into the LAN or onto the phone is refused before it is requested (plan review G4).
                    if (!UrlImportRules.isLocalHost(original.host) && resolvesLocal(step.url.host)) return UrlImportResult.Failed(FetchFailure.LocalTarget)
                    current = step.url; hops++
                }
                is Step.Done -> return step.result
            }
        }
    }

    /**
     * True when [host] resolves to a local address. An unknown name is left to the connect, which reports it. The platform resolves
     * again at connect time; a server that changes its DNS answer between the two lookups is the accepted residual (a blind GET with
     * no credentials).
     */
    private suspend fun resolvesLocal(host: String): Boolean {
        val addresses = try { abandonable { resolve(host) } } catch (_: UnknownHostException) { return false }
        return addresses.any(UrlImportRules::isLocalAddress)
    }

    private suspend fun step(original: URL, current: URL, hops: Int, store: WordlistStore, onProgress: (Long, Long?) -> Unit): Step {
        val conn = open(current)
        try {
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = URL_CONNECT_TIMEOUT_MS
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty(USER_AGENT_HEADER, HttpClient.USER_AGENT)
            conn.setRequestProperty(ACCEPT_HEADER, ACCEPT)
            val code = try {
                abandonable { conn.responseCode }   // cancel abandons the thread; the finally below disconnects
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()   // a disconnect from Cancel is the cancellation, not a fetch failure
                return failed(UrlImportRules.failureOf(e))
            }
            if (code in REDIRECT_CODES) return redirect(original, current, conn.getHeaderField(LOCATION_HEADER), hops, code)
            if (code != HTTP_OK) return failed(FetchFailure.Status(code))
            val type = conn.contentType
            if (!UrlImportRules.contentType(type)) return failed(FetchFailure.NotText(UrlImportRules.mediaType(type)))
            val length = conn.contentLengthLong
            if (length > WordlistStore.MAX_IMPORT_BYTES) return Step.Done(UrlImportResult.Stored(ImportResult.TooLarge))   // no body byte is read
            return Step.Done(readBody(conn, current, length.takeIf { it >= 0 }, store, onProgress))
        } finally {
            runCatching { conn.disconnect() }   // cleanup only: a failed disconnect has nothing left to release
        }
    }

    /**
     * Runs a blocking call (a DNS lookup, the status read) on its own IO thread and awaits it, so cancelling the caller returns at once
     * and the blocked thread is abandoned: getaddrinfo cannot be interrupted, and a lookup can outlast the resolver timeout (spec 5.1).
     * The abandoned thread ends when the call does; the finally in [step] disconnects the connection it was using.
     */
    private suspend fun <T> abandonable(block: () -> T): T {
        val result = CompletableDeferred<T>()
        CoroutineScope(Dispatchers.IO).launch {
            try { result.complete(block()) } catch (t: Throwable) { result.completeExceptionally(t) }
        }
        return result.await()
    }

    private fun failed(f: FetchFailure): Step = Step.Done(UrlImportResult.Failed(f))

    private fun redirect(original: URL, current: URL, location: String?, hops: Int, code: Int): Step =
        when (val d = UrlImportRules.redirect(original, current, location, hops)) {
            is RedirectDecision.Follow -> Step.Redirect(d.url)
            RedirectDecision.TooMany -> failed(FetchFailure.TooManyRedirects)
            RedirectDecision.Insecure -> failed(FetchFailure.Insecure)
            RedirectDecision.LocalTarget -> failed(FetchFailure.LocalTarget)
            RedirectDecision.Invalid -> failed(FetchFailure.Status(code))
        }

    /**
     * The body runs NonCancellable so a Cancel never lands inside the store's commit: the watcher disconnects at once, the store's read
     * fails (Unreadable, nothing written), and a list that finished storing in the same instant is deleted before Cancelled is reported.
     * The enclosing coroutineScope then ends the job with CancellationException.
     */
    private suspend fun readBody(conn: HttpURLConnection, finalUrl: URL, total: Long?, store: WordlistStore, onProgress: (Long, Long?) -> Unit): UrlImportResult {
        val outer = currentCoroutineContext()[Job]
        return disconnectOnCancel(conn) {
            withContext(NonCancellable) {
                val r = importBody(conn, finalUrl, total, store, onProgress)
                if (outer?.isActive == false) {
                    ((r as? UrlImportResult.Stored)?.result as? ImportResult.Imported)?.let { store.delete(it.meta.id) }
                    UrlImportResult.Cancelled
                } else r
            }
        }
    }

    private suspend fun importBody(conn: HttpURLConnection, finalUrl: URL, total: Long?, store: WordlistStore, onProgress: (Long, Long?) -> Unit): UrlImportResult {
        onProgress(0, total)
        val raw = try { conn.inputStream } catch (e: IOException) { return UrlImportResult.Failed(UrlImportRules.failureOf(e)) }
        val recording = ErrorRecordingStream(CountingStream(raw, total, onProgress))
        val buffered = BufferedInputStream(recording, SNIFF_BUFFER_BYTES)
        val prefix = try {
            buffered.mark(UrlImportRules.BINARY_SNIFF_BYTES)
            readUpTo(buffered, UrlImportRules.BINARY_SNIFF_BYTES).also { buffered.reset() }
        } catch (e: IOException) {
            runCatching { buffered.close() }   // cleanup only
            return bodyFailure(recording.lastError ?: e) ?: UrlImportResult.Stored(ImportResult.Unreadable)
        }
        if (UrlImportRules.looksBinary(prefix)) {
            runCatching { buffered.close() }   // cleanup only
            return UrlImportResult.Failed(FetchFailure.NotText(null))
        }
        val stored = store.import(UrlImportRules.displayName(finalUrl), buffered)   // the store closes the stream
        // A network fault mid-body is a fetch failure, not a file-format one: the store only sees an IOException as Unreadable.
        val fault = recording.lastError
        if (stored == ImportResult.Unreadable && fault != null) bodyFailure(fault)?.let { return it }
        return UrlImportResult.Stored(stored)
    }

    /** The fetch failure for a body-phase IOException, or null when the job was cancelled (the disconnect is then the cancellation, not a fault). */
    private suspend fun bodyFailure(e: IOException): UrlImportResult? =
        if (currentCoroutineContext()[Job]?.isActive == false) null else UrlImportResult.Failed(UrlImportRules.failureOf(e))

    /** Disconnects [conn] the moment the caller is cancelled, so a read blocked inside [block] ends at once instead of at its timeout. */
    private suspend fun <T> disconnectOnCancel(conn: HttpURLConnection, block: suspend () -> T): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!finished.get()) runCatching { conn.disconnect() } }
        }
        try { block() } finally { finished.set(true); watcher.cancel() }
    }

    private fun readUpTo(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) break
            got += r
        }
        return buf.copyOf(got)
    }

    companion object {
        const val URL_CONNECT_TIMEOUT_MS = 10_000
        const val URL_READ_TIMEOUT_MS = 15_000
        /** Progress reaches the pill at most once per this many bytes (spec 5.1). */
        const val PROGRESS_STEP_BYTES = 65_536L
        private const val SNIFF_BUFFER_BYTES = UrlImportRules.BINARY_SNIFF_BYTES * 2
        private const val HTTP_OK = 200
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val USER_AGENT_HEADER = "User-Agent"
        private const val ACCEPT_HEADER = "Accept"
        private const val ACCEPT = "text/plain, */*;q=0.5"
        private const val LOCATION_HEADER = "Location"
    }
}

/** Remembers the last IOException the network stream threw, so a mid-body fault is told apart from a bad file. */
internal class ErrorRecordingStream(inner: InputStream) : FilterInputStream(inner) {
    @Volatile var lastError: IOException? = null
        private set
    override fun read(): Int = try { super.read() } catch (e: IOException) { lastError = e; throw e }
    override fun read(b: ByteArray, off: Int, len: Int): Int = try { super.read(b, off, len) } catch (e: IOException) { lastError = e; throw e }
}

/** Counts the bytes read from the network (after any transparent gzip) and reports at most every PROGRESS_STEP_BYTES. */
internal class CountingStream(inner: InputStream, private val total: Long?, private val onProgress: (Long, Long?) -> Unit) : FilterInputStream(inner) {
    private var count = 0L
    private var reported = 0L
    override fun read(): Int {
        val b = super.read()
        if (b >= 0) advance(1) else checkComplete()
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) advance(n.toLong()) else if (n < 0) checkComplete()
        return n
    }
    /** A body that ends before its declared Content-Length is a dropped connection, never a complete list (final review S2). */
    private fun checkComplete() {
        if (total != null && count < total) throw EOFException("body ended at $count of $total bytes")
    }
    private fun advance(n: Long) {
        count += n
        if (count - reported >= UrlWordlistFetcher.PROGRESS_STEP_BYTES) { reported = count; onProgress(count, total) }
    }
}
