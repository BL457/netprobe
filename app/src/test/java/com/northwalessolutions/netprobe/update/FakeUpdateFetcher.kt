package com.northwalessolutions.netprobe.update

import java.io.OutputStream
import java.net.URL

/** Never touches the network: serves one canned body and records every request. */
class FakeUpdateFetcher(var body: ByteArray = ByteArray(0), var failure: FetchError? = null,
                        private val beforeFinish: suspend () -> Unit = {}) : UpdateFetcher {
    val requested = mutableListOf<String>()
    override suspend fun fetch(url: URL, maxBytes: Long, out: OutputStream, onProgress: (Long, Long?) -> Unit): UpdateFetch {
        requested += url.toString()
        failure?.let { return UpdateFetch.Failed(it) }
        out.write(body)
        out.flush()
        onProgress(body.size.toLong(), body.size.toLong())
        beforeFinish()
        return UpdateFetch.Ok(body.size.toLong())
    }
}

class FakePrefs(override var lastCheckEpochMs: Long = 0L, override var skippedVersionCode: Int = 0) : UpdatePrefs
