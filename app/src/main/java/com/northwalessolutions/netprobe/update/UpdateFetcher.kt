package com.northwalessolutions.netprobe.update

import com.northwalessolutions.netprobe.engine.common.HttpClient
import com.northwalessolutions.netprobe.wordlists.UrlImportRules
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

enum class FetchError { REFUSED, TOO_MANY_REDIRECTS, TOO_LARGE, STATUS, CONNECTION }

sealed interface UpdateFetch {
    data class Ok(val bytes: Long) : UpdateFetch
    data class Failed(val error: FetchError) : UpdateFetch
}

/** One HTTPS GET into [out], capped at maxBytes. Cancelling the caller ends the request. Fakes stand in for it in tests. */
interface UpdateFetcher {
    suspend fun fetch(url: URL, maxBytes: Long, out: OutputStream, onProgress: (Long, Long?) -> Unit = { _, _ -> }): UpdateFetch
}

sealed interface UpdateRedirect {
    data class Follow(val url: URL) : UpdateRedirect
    data object TooMany : UpdateRedirect
    /** An http hop, another scheme, credentials, a local target or no usable Location. */
    data object Refused : UpdateRedirect
}

/** Pure redirect rules (spec 1): https to https across hosts, at most [UpdateConfig.MAX_REDIRECTS]; never http, never a local host. */
object UpdateRedirects {
    fun allowedStart(url: URL): Boolean =
        url.protocol.equals("https", ignoreCase = true) && !url.host.isNullOrEmpty() && url.userInfo == null && !UrlImportRules.isLocalHost(url.host)

    fun next(from: URL, location: String?, hops: Int): UpdateRedirect {
        if (hops >= UpdateConfig.MAX_REDIRECTS) return UpdateRedirect.TooMany
        val loc = location?.trim()?.takeIf { it.isNotEmpty() } ?: return UpdateRedirect.Refused
        val target = try { URL(from, loc) } catch (_: MalformedURLException) { return UpdateRedirect.Refused }
        return if (allowedStart(target)) UpdateRedirect.Follow(URL(target.toString().substringBefore('#'))) else UpdateRedirect.Refused
    }
}

/**
 * HttpURLConnection fetcher for the manifest and the APK. Redirects are followed here, never by the platform, so every hop passes
 * [UpdateRedirects]. No Authorization or Cookie is sent. The wordlist fetcher is not reused: it refuses non-text content types and
 * binary bodies, which is exactly what an APK is, and it allows three hops where GitHub's release path can need more.
 */
class HttpsUpdateFetcher(private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) : UpdateFetcher {
    override suspend fun fetch(url: URL, maxBytes: Long, out: OutputStream, onProgress: (Long, Long?) -> Unit): UpdateFetch =
        withContext(Dispatchers.IO) {
            if (!UpdateRedirects.allowedStart(url)) return@withContext UpdateFetch.Failed(FetchError.REFUSED)
            var current = url
            var hops = 0
            var result: UpdateFetch? = null
            while (result == null) {
                val conn = open(current)
                var next: URL? = null
                try {
                    result = disconnectOnCancel(conn) { hop(conn, current, hops, maxBytes, out, onProgress) { next = it } }
                } finally {
                    runCatching { conn.disconnect() }   // cleanup only
                }
                if (result == null) { current = checkNotNull(next); hops++ }
            }
            checkNotNull(result)
        }

    /** One request. Returns the final result, or null after reporting a redirect target through [follow]. */
    private suspend fun hop(conn: HttpURLConnection, current: URL, hops: Int, maxBytes: Long, out: OutputStream,
                            onProgress: (Long, Long?) -> Unit, follow: (URL) -> Unit): UpdateFetch? {
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
        conn.readTimeout = UpdateConfig.READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", HttpClient.USER_AGENT)
        try {
            val code = conn.responseCode
            if (code in REDIRECT_CODES) {
                return when (val d = UpdateRedirects.next(current, conn.getHeaderField("Location"), hops)) {
                    is UpdateRedirect.Follow -> { follow(d.url); null }
                    UpdateRedirect.TooMany -> UpdateFetch.Failed(FetchError.TOO_MANY_REDIRECTS)
                    UpdateRedirect.Refused -> UpdateFetch.Failed(FetchError.REFUSED)
                }
            }
            if (code != HTTP_OK) return UpdateFetch.Failed(FetchError.STATUS)
            val length = conn.contentLengthLong
            if (length > maxBytes) return UpdateFetch.Failed(FetchError.TOO_LARGE)
            return copyBody(conn, length.takeIf { it >= 0 }, maxBytes, out, onProgress)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()   // a disconnect from Cancel is the cancellation, not a fetch failure
            return UpdateFetch.Failed(FetchError.CONNECTION)
        }
    }

    private suspend fun copyBody(conn: HttpURLConnection, total: Long?, maxBytes: Long, out: OutputStream, onProgress: (Long, Long?) -> Unit): UpdateFetch {
        var count = 0L
        var reported = 0L
        conn.inputStream.use { input ->
            val buf = ByteArray(BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                count += n
                if (count > maxBytes) return UpdateFetch.Failed(FetchError.TOO_LARGE)
                out.write(buf, 0, n)
                if (count - reported >= UpdateConfig.PROGRESS_STEP_BYTES) { reported = count; onProgress(count, total) }
            }
        }
        if (total != null && count < total) return UpdateFetch.Failed(FetchError.CONNECTION)   // dropped mid-body
        onProgress(count, total)
        return UpdateFetch.Ok(count)
    }

    /** Disconnects [conn] the moment the caller is cancelled, so a blocked read ends at once instead of at its timeout. */
    private suspend fun <T> disconnectOnCancel(conn: HttpURLConnection, block: suspend () -> T): T = coroutineScope {
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { runCatching { conn.disconnect() } }
        }
        try { block() } finally { watcher.cancel() }
    }

    private companion object {
        const val HTTP_OK = 200
        const val BUFFER_BYTES = 16 * 1024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
